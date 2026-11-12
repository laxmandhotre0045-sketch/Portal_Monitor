<?php
// =====================================================================
//  Portal Uptime Monitor - main checker
//  Run by Hostinger cron every 1-5 minutes:
//      /usr/bin/php /home/<user>/.../monitor/check.php
// =====================================================================
declare(strict_types=1);

// =====================================================================
//  RETIRED. This is the 1.x PHP checker. Version 2 is the Java service
//  (portal-monitor.jar --mode server) and uses a different schema:
//  this script writes columns that no longer exist (check_logs.error,
//  portals.last_error) and would treat all 31 CONTAINER rows, which have
//  no health_url, as DOWN - a false alert for every container.
//
//  Running it next to the Java server would also double-count failures
//  and send duplicate emails. REMOVE its cron job in hPanel
//  (Advanced -> Cron Jobs). It now exits immediately on purpose.
// =====================================================================
if (PHP_SAPI !== 'cli') {
    http_response_code(410);
    header('Content-Type: text/plain; charset=utf-8');
}
echo "check.php is retired: Portal Monitor 2.x runs as the Java service. Remove this cron job.", PHP_EOL;
exit(0);

require __DIR__ . '/config.php';
require __DIR__ . '/mailer.php';

date_default_timezone_set(TIMEZONE);

// ---------- Allow only cron (CLI) or a URL call carrying the secret key ----------
if (PHP_SAPI !== 'cli') {
    if (!hash_equals(CRON_SECRET, (string)($_GET['key'] ?? ''))) {
        http_response_code(403);
        exit('Forbidden');
    }
    header('Content-Type: text/plain; charset=utf-8');
}

function logmsg(string $msg): void
{
    echo '[' . date('Y-m-d H:i:s') . '] ' . $msg . PHP_EOL;
}

// ---------- Prevent two runs overlapping (if a run is slow) ----------
$lock = fopen(__DIR__ . '/monitor.lock', 'c');
if ($lock === false || !flock($lock, LOCK_EX | LOCK_NB)) {
    logmsg('Previous run still in progress - skipping.');
    exit(0);
}

// ---------- Database ----------
try {
    $pdo = new PDO(
        'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
        DB_USER,
        DB_PASS,
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
    );
    // Keep MySQL time in sync with PHP time zone
    $pdo->exec("SET time_zone = '" . (new DateTime())->format('P') . "'");
} catch (PDOException $e) {
    logmsg('DB connection failed: ' . $e->getMessage());
    exit(1);
}

$portals = $pdo->query('SELECT * FROM portals WHERE is_active = 1 ORDER BY id')->fetchAll();
if (!$portals) {
    logmsg('No active portals in the database.');
    exit(0);
}

// ---------- Hit all health URLs in parallel ----------
function check_all(array $portals): array
{
    $mh = curl_multi_init();
    $handles = [];

    foreach ($portals as $p) {
        $ch = curl_init($p['health_url']);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CONNECTTIMEOUT => CONNECT_TIMEOUT,
            CURLOPT_TIMEOUT        => REQUEST_TIMEOUT,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => VERIFY_SSL,
            CURLOPT_SSL_VERIFYHOST => VERIFY_SSL ? 2 : 0,
            CURLOPT_USERAGENT      => 'PortalMonitor/1.0',
            CURLOPT_HTTPHEADER     => ['Accept: application/json, text/plain, */*'],
        ]);
        curl_multi_add_handle($mh, $ch);
        $handles[(int)$p['id']] = $ch;
    }

    do {
        $status = curl_multi_exec($mh, $running);
        if ($running) {
            curl_multi_select($mh, 1.0);
        }
    } while ($running && $status === CURLM_OK);

    // Collect the real transport error per handle (timeout, refused, DNS...)
    $curlErrors = [];
    while ($info = curl_multi_info_read($mh)) {
        if ($info['result'] !== CURLE_OK) {
            $curlErrors[spl_object_id($info['handle'])] = curl_strerror($info['result']);
        }
    }

    $results = [];
    foreach ($handles as $id => $ch) {
        $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $ms   = (int)round(curl_getinfo($ch, CURLINFO_TOTAL_TIME) * 1000);
        $err  = $curlErrors[spl_object_id($ch)] ?? curl_error($ch);

        if (in_array($code, SUCCESS_CODES, true)) {
            $error = null;
        } elseif ($code === 0) {
            $error = $err !== '' ? $err : 'No response';
        } else {
            $error = 'HTTP ' . $code;
        }

        $results[$id] = ['code' => $code, 'ms' => $ms, 'error' => $error !== null ? mb_substr($error, 0, 250) : null];
        curl_multi_remove_handle($mh, $ch);
    }
    curl_multi_close($mh);
    return $results;
}

// ---------- Email content ----------
function human_duration(string $from, string $to): string
{
    $s = max(0, strtotime($to) - strtotime($from));
    $d = intdiv($s, 86400); $s %= 86400;
    $h = intdiv($s, 3600);  $s %= 3600;
    $m = intdiv($s, 60);    $s %= 60;
    $out = [];
    if ($d) $out[] = "{$d}d";
    if ($h) $out[] = "{$h}h";
    if ($m) $out[] = "{$m}m";
    if (!$out || (!$d && !$h)) $out[] = "{$s}s";
    return implode(' ', $out);
}

function build_email(string $type, array $p, array $r, string $failSince, string $now, int $failCount): array
{
    $e = fn($v) => htmlspecialchars((string)$v, ENT_QUOTES, 'UTF-8');

    if ($type === 'DOWN') {
        $subject = "🔴 DOWN: {$p['portal_name']}";
        $color   = '#c0392b';
        $title   = "{$p['portal_name']} is DOWN";
        $rows = [
            'Portal'               => $p['portal_name'],
            'Health URL'           => $p['health_url'],
            'Result'               => $r['code'] === 0 ? 'No response' : 'HTTP ' . $r['code'],
            'Error'                => $r['error'] ?? '-',
            'Failing since'        => $failSince,
            'Consecutive failures' => (string)$failCount,
            'Detected at'          => $now,
        ];
    } else {
        $subject = "🟢 RECOVERED: {$p['portal_name']}";
        $color   = '#27ae60';
        $title   = "{$p['portal_name']} is back UP";
        $rows = [
            'Portal'        => $p['portal_name'],
            'Health URL'    => $p['health_url'],
            'Result'        => 'HTTP ' . $r['code'],
            'Response time' => $r['ms'] . ' ms',
            'Was down from' => $failSince,
            'Recovered at'  => $now,
            'Total downtime'=> human_duration($failSince, $now),
        ];
    }

    $htmlRows = '';
    $text     = $title . "\n\n";
    foreach ($rows as $k => $v) {
        $htmlRows .= '<tr><td style="padding:6px 12px;color:#555;border-bottom:1px solid #eee"><b>' . $e($k) . '</b></td>'
                   . '<td style="padding:6px 12px;border-bottom:1px solid #eee">' . $e($v) . '</td></tr>';
        $text     .= str_pad($k . ':', 22) . $v . "\n";
    }

    $html = '<div style="font-family:Arial,sans-serif;max-width:600px">'
          . '<div style="background:' . $color . ';color:#fff;padding:14px 16px;font-size:18px;font-weight:bold">' . $e($title) . '</div>'
          . '<table style="border-collapse:collapse;width:100%;font-size:14px">' . $htmlRows . '</table>'
          . '<p style="color:#888;font-size:12px;margin-top:12px">Automated alert from Portal Monitor.</p></div>';

    return [$subject, $html, $text];
}

function send_alert(PDO $pdo, string $type, array $p, array $r, string $failSince, string $now, int $failCount): void
{
    [$subject, $html, $text] = build_email($type, $p, $r, $failSince, $now, $failCount);
    $to = trim((string)$p['notify_email']) !== '' ? $p['notify_email'] : DEFAULT_NOTIFY_EMAIL;
    [$ok, $err] = send_mail($to, $subject, $html, $text);

    $pdo->prepare('INSERT INTO alert_logs (portal_id, alert_type, sent_to, success, error, sent_at) VALUES (?,?,?,?,?,?)')
        ->execute([$p['id'], $type, $to, $ok ? 1 : 0, $err ? mb_substr($err, 0, 490) : null, $now]);

    logmsg("  -> {$type} email to {$to}: " . ($ok ? 'sent' : 'FAILED (' . $err . ')'));
}

// ---------- Main loop ----------
$results = check_all($portals);
$now     = date('Y-m-d H:i:s');

$insertLog = $pdo->prepare(
    'INSERT INTO check_logs (portal_id, http_code, response_ms, is_up, error, checked_at) VALUES (?,?,?,?,?,?)'
);
$updatePortal = $pdo->prepare(
    'UPDATE portals SET status = ?, fail_count = ?, fail_since = ?, last_http_code = ?, last_response_ms = ?,
                        last_error = ?, last_checked = ?, last_status_change = ?
     WHERE id = ?'
);

foreach ($portals as $p) {
    $id   = (int)$p['id'];
    $r    = $results[$id];
    $isUp = in_array($r['code'], SUCCESS_CODES, true);

    $insertLog->execute([$id, $r['code'], $r['ms'], $isUp ? 1 : 0, $r['error'], $now]);

    $oldStatus    = $p['status'];
    $newStatus    = $oldStatus;
    $statusChange = $p['last_status_change'];

    if ($isUp) {
        // Healthy: clear failure streak; alert only if it WAS down
        if ($oldStatus === 'DOWN') {
            send_alert($pdo, 'RECOVERED', $p, $r, $p['fail_since'] ?? $now, $now, 0);
        }
        if ($oldStatus !== 'UP') {
            $newStatus    = 'UP';
            $statusChange = $now;
        }
        $failCount = 0;
        $failSince = null;
    } else {
        // Unhealthy: count consecutive failures; alert once when threshold is reached
        $failCount = (int)$p['fail_count'] + 1;
        $failSince = $p['fail_since'] ?? $now;

        if ($oldStatus !== 'DOWN' && $failCount >= FAIL_THRESHOLD) {
            $newStatus    = 'DOWN';
            $statusChange = $now;
            send_alert($pdo, 'DOWN', $p, $r, $failSince, $now, $failCount);
        }
    }

    $updatePortal->execute([$newStatus, $failCount, $failSince, $r['code'], $r['ms'], $r['error'], $now, $statusChange, $id]);

    logmsg(sprintf('%-25s %-5s code=%-3d %5dms fails=%d%s',
        $p['portal_name'], $newStatus, $r['code'], $r['ms'], $failCount,
        $r['error'] ? '  (' . $r['error'] . ')' : ''));
}

// ---------- Housekeeping: trim old history ----------
$deleted = $pdo->exec('DELETE FROM check_logs WHERE checked_at < NOW() - INTERVAL ' . (int)LOG_RETENTION_DAYS . ' DAY');
if ($deleted) {
    logmsg("Deleted {$deleted} old check_logs rows.");
}

flock($lock, LOCK_UN);
fclose($lock);
logmsg('Done. Checked ' . count($portals) . ' portal(s).');
