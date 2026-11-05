# -*- coding: utf-8 -*-
"""S1 回灌：把企微实际绑定里「火马漏记」的 (客户,标签) 对补进本地 customer_tag。

来源快照：/tmp/recon_wecom.tsv (wid\teid)，由 reconcile_tag_vs_wecom.py census 产出（企微全量枚举）。
本地现状：customer_tag ⋈ tag ⋈ customer。

缺口 = 企微快照里有、本地 customer_tag 里没有的 (wid, eid) 对。
补法（只写本地，不动企微 —— 企微侧本就有这些绑定）：
  wid → 本地 tag 行（优先 gk == 企微组的 canonical；歧义时取最小 id）
  eid → customer.id
  INSERT IGNORE INTO customer_tag(customer_id, tag_id, source, tagged_at)   ← 幂等

子命令：
  plan    只读：算缺口 + 可解析率（eid 无 customer / wid 无 tag 行 / wid 多行）
  apply   写库：备份 customer_tag → 建 wid 映射 → 批量 INSERT IGNORE
  verify  只读：重算覆盖率（复用 reconcile 口径）

产物：/tmp/backfill_missing.tsv  /tmp/s1_backfill.log
"""
import json
import subprocess
import sys
import time
import urllib.request

WECOM_TSV = '/tmp/recon_wecom.tsv'
MISS_TSV = '/tmp/backfill_missing.tsv'
LOG = '/tmp/s1_backfill.log'
DB = 'bookstore_qrcode'

CMD = next((a for a in sys.argv[1:] if not a.startswith('-')), 'plan')
SRC = next((a.split('=', 1)[1] for a in sys.argv[1:] if a.startswith('--source=')), 'system')

env = {}
for line in open('/etc/systemd/system/huoma.env'):
    s = line.strip()
    if s and not s.startswith('#') and '=' in s:
        k, v = s.split('=', 1)
        env[k.strip()] = v.strip()


def log(msg):
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(msg + '\n')
    print(msg, flush=True)


def mysql_args():
    return ['mysql', '-h', env['DB_HOST'], '-u', env['DB_USERNAME'],
            '-p' + env['DB_PASSWORD'], '-N', '-B', DB]


def sql(q):
    return subprocess.run(mysql_args() + ['-e', q],
                          capture_output=True, text=True).stdout.strip().splitlines()


def sql_in(text):
    r = subprocess.run(mysql_args(), input=text, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit('mysql 失败: ' + r.stderr.strip())
    return r.stdout.strip()


def read_set(p):
    s = set()
    with open(p, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if line:
                s.add(line)
    return s


_tok = {'v': None, 'at': 0}


def token(force=False):
    if force or not _tok['v'] or time.time() - _tok['at'] > 6000:
        d = json.loads(urllib.request.urlopen(
            'https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=' + env['WECOM_CORP_ID']
            + '&corpsecret=' + env['WECOM_CORP_SECRET'], timeout=20).read())
        _tok['v'] = d['access_token']
        _tok['at'] = time.time()
    return _tok['v']


def api(path, body=None):
    if body is None:
        url = ('https://qyapi.weixin.qq.com/cgi-bin/' + path
               + ('&' if '?' in path else '?') + 'access_token=' + token())
        return json.loads(urllib.request.urlopen(url, timeout=30).read())
    req = urllib.request.Request(
        'https://qyapi.weixin.qq.com/cgi-bin/' + path + '?access_token=' + token(),
        data=json.dumps(body).encode('utf-8'),
        headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())


def wid_group():
    d = api('externalcontact/get_corp_tag_list?')
    m = {}
    for g in d.get('tag_group', []):
        for t in g.get('tag', []):
            m[t['id']] = g.get('group_name', '')
    return m


def load_tmp_bf(stmts, pairs):
    batch = []

    def q(x):
        return str(x).replace('\\', '\\\\').replace("'", "''")

    def flush():
        if batch:
            stmts.append('INSERT INTO tmp_bf VALUES '
                         + ','.join("('%s','%s')" % (q(w), q(e)) for w, e in batch) + ';')
            batch.clear()

    for w, e in pairs:
        batch.append((w, e))
        if len(batch) >= 2000:
            flush()
    flush()


def cmd_plan():
    log('=== plan %s ===' % time.strftime('%F %T'))
    W = read_set(WECOM_TSV)
    L = set(sql("SELECT t.wecom_tag_id, c.external_userid FROM customer_tag ct "
                "JOIN tag t ON ct.tag_id=t.id JOIN customer c ON ct.customer_id=c.id "
                "WHERE t.wecom_tag_id IS NOT NULL AND t.wecom_tag_id<>''"))
    miss = W - L
    log('企微快照对 = %d' % len(W))
    log('本地现状对 = %d' % len(L))
    log('缺口(待回灌) = %d' % len(miss))
    with open(MISS_TSV, 'w', encoding='utf-8') as f:
        f.write('\n'.join(sorted(miss)) + '\n')

    stmts = ['DROP TEMPORARY TABLE IF EXISTS tmp_bf;',
             'CREATE TEMPORARY TABLE tmp_bf(wid VARCHAR(300), eid VARCHAR(300), '
             'KEY(wid), KEY(eid));']
    load_tmp_bf(stmts, (p.split('\t', 1) for p in miss))
    stmts += [
        "SELECT 'wids', COUNT(DISTINCT wid) FROM tmp_bf;",
        "SELECT 'eids', COUNT(DISTINCT eid) FROM tmp_bf;",
        "SELECT 'eid_no_customer', COUNT(*) FROM tmp_bf b "
        "LEFT JOIN customer c ON c.external_userid=b.eid WHERE c.id IS NULL;",
        "SELECT 'wid_no_tag', COUNT(DISTINCT b.wid) FROM tmp_bf b "
        "LEFT JOIN tag t ON t.wecom_tag_id=b.wid WHERE t.id IS NULL;",
        "SELECT 'wid_multirow', COUNT(*) FROM (SELECT b.wid FROM tmp_bf b "
        "JOIN tag t ON t.wecom_tag_id=b.wid GROUP BY b.wid "
        "HAVING COUNT(DISTINCT t.id)>1) x;",
        "SELECT 'resolvable_pairs', COUNT(*) FROM tmp_bf b "
        "JOIN customer c ON c.external_userid=b.eid "
        "JOIN tag t ON t.wecom_tag_id=b.wid;",
        "SHOW COLUMNS FROM customer_tag LIKE 'source';",
    ]
    log('--- 可解析率 ---')
    log(sql_in('\n'.join(stmts)))
    log('缺口明细 -> %s' % MISS_TSV)


def build_wid_map():
    grp = wid_group()
    rows = sql("SELECT id, wecom_tag_id, group_keyword FROM tag "
               "WHERE wecom_tag_id IS NOT NULL AND wecom_tag_id<>''")
    by = {}
    for r in rows:
        i, wid, gk = r.split('\t')
        by.setdefault(wid, []).append((int(i), gk))
    wid_map = {}
    for wid, lst in by.items():
        g = grp.get(wid, '')
        cand = [i for i, gk in lst if gk == g]
        wid_map[wid] = min(cand) if cand else min(i for i, _ in lst)
    return wid_map


def cmd_apply():
    miss = read_set(MISS_TSV)
    wid_map = build_wid_map()
    bak = 'bak_ct_s1_' + time.strftime('%Y%m%d_%H%M%S')
    log('=== apply %s === 备份表 %s' % (time.strftime('%F %T'), bak))
    stmts = ['CREATE TABLE %s AS SELECT * FROM customer_tag;' % bak,
             'DROP TEMPORARY TABLE IF EXISTS tmp_bf;',
             'CREATE TEMPORARY TABLE tmp_bf(wid VARCHAR(300), eid VARCHAR(300), '
             'KEY(wid), KEY(eid));']
    load_tmp_bf(stmts, (p.split('\t', 1) for p in miss))
    stmts += ['DROP TEMPORARY TABLE IF EXISTS tmp_map;',
              'CREATE TEMPORARY TABLE tmp_map(wid VARCHAR(300), tag_id INT, KEY(wid));']
    mb = []
    for wid, tid in wid_map.items():
        mb.append("('%s',%d)" % (str(wid).replace("'", "''"), tid))
        if len(mb) >= 2000:
            stmts.append('INSERT INTO tmp_map VALUES ' + ','.join(mb) + ';')
            mb = []
    if mb:
        stmts.append('INSERT INTO tmp_map VALUES ' + ','.join(mb) + ';')
    stmts += [
        "INSERT IGNORE INTO customer_tag(customer_id, tag_id, source, tagged_at) "
        "SELECT c.id, m.tag_id, '%s', NOW() FROM tmp_bf b "
        "JOIN tmp_map m ON m.wid=b.wid JOIN customer c ON c.external_userid=b.eid;" % SRC,
        "SELECT 'inserted', ROW_COUNT();",
    ]
    log(sql_in('\n'.join(stmts)))
    log('apply 完成，备份 %s（回滚：DROP inserted 后从 bak 恢复）' % bak)


def cmd_verify():
    log('=== verify %s ===' % time.strftime('%F %T'))
    W = read_set(WECOM_TSV)
    L = set(sql("SELECT t.wecom_tag_id, c.external_userid FROM customer_tag ct "
                "JOIN tag t ON ct.tag_id=t.id JOIN customer c ON ct.customer_id=c.id "
                "WHERE t.wecom_tag_id IS NOT NULL AND t.wecom_tag_id<>''"))
    both = len(W & L)
    log('企微 = %d  火马 = %d  一致 = %d  覆盖率 = %.2f%%'
        % (len(W), len(L), both, 100.0 * both / len(W) if W else 0))
    log('企微独有(漏记) = %d  火马独有(多记) = %d' % (len(W - L), len(L - W)))


SUBS = {'plan': cmd_plan, 'apply': cmd_apply, 'verify': cmd_verify}

if __name__ == '__main__':
    SUBS.get(CMD, cmd_plan)()
