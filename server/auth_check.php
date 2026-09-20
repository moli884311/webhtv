<?php
/*
 * 授权校验接口
 *
 * 读取:
 *   GET /auth_check.php?d=<设备码>&p=<包名>&v=<versionCode>
 *   返回 JSON:
 *     {"ok":1,"auth":0|1,"exp":<到期unix>,"srv":<服务器unix>,"d":..,"p":..,"sign":"<base64>"}
 *   sign = base64(RSA-SHA256("d|p|auth|exp|srv"))，App 内嵌公钥验签。
 *
 * 写入授权记录由 QQ 机器人的「授权」指令完成，记录落在
 *   /www/wwwroot/tvbox_licenses.json  (站点目录外，web 不可访问)
 * 格式: {"devices":{"<设备码>":{"exp":<unix>,"pkg":"<包名或空>","by":"<操作人>","at":<unix>}}}
 *
 * 私钥 /www/wwwroot/tvbox_license_rsa.key 权限 640 root:www，站点目录外。
 */
date_default_timezone_set('Asia/Shanghai');
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Robots-Tag: noindex');

define('KEY_FILE', '/www/wwwroot/tvbox_license_rsa.key');
define('STORE_FILE', '/www/wwwroot/tvbox_licenses.json');

function lic_out($d, $p, $auth, $exp, $srv, $key) {
    $canon = $d . '|' . $p . '|' . (int)$auth . '|' . (int)$exp . '|' . (int)$srv;
    $sig = '';
    if ($key) {
        @openssl_sign($canon, $sig, $key, OPENSSL_ALGO_SHA256);
    }
    echo json_encode(array(
        'ok' => 1,
        'auth' => (int)$auth,
        'exp' => (int)$exp,
        'srv' => (int)$srv,
        'd' => (string)$d,
        'p' => (string)$p,
        'sign' => base64_encode($sig),
    ), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

$d = isset($_GET['d']) ? trim((string)$_GET['d']) : '';
$p = isset($_GET['p']) ? trim((string)$_GET['p']) : '';
$srv = time();

if ($d === '' || strlen($d) > 64 || !preg_match('/^[A-Za-z0-9_-]+$/', $d)) {
    echo json_encode(array('ok' => 0, 'auth' => 0, 'exp' => 0, 'srv' => $srv,
        'd' => $d, 'p' => $p, 'sign' => ''), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

$key = @file_get_contents(KEY_FILE);
if ($key === false || $key === '') {
    echo json_encode(array('ok' => 0, 'auth' => 0, 'exp' => 0, 'srv' => $srv,
        'd' => $d, 'p' => $p, 'sign' => ''), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

$auth = 0;
$exp = 0;
$raw = @file_get_contents(STORE_FILE);
$data = $raw === false ? null : json_decode($raw, true);
$lookup = strtoupper($d);
if (is_array($data) && isset($data['devices'][$lookup]) && is_array($data['devices'][$lookup])) {
    $rec = $data['devices'][$lookup];
    $e = isset($rec['exp']) ? (int)$rec['exp'] : 0;
    $rp = isset($rec['pkg']) ? trim((string)$rec['pkg']) : '';
    if ($e > $srv) {
        $exp = $e;
        if ($rp === '' || $rp === $p) {
            $auth = 1;
        }
    }
}

lic_out($d, $p, $auth, $exp, $srv, $key);
