# -*- coding: utf-8 -*-
"""复核：R1 补打的 1198 人，现在是否都带上了目标年级标签。只读。"""
import json
import time
import urllib.request

BACKFILL = '/tmp/r1_backfill_result.tsv'
LOG = '/tmp/r1_verify.log'

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


_tok = {'v': None, 'at': 0}


def token(force=False):
    if force or not _tok['v'] or time.time() - _tok['at'] > 6000:
        d = json.loads(urllib.request.urlopen(
            'https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=' + env['WECOM_CORP_ID']
            + '&corpsecret=' + env['WECOM_CORP_SECRET'], timeout=20).read())
        _tok['v'] = d['access_token']
        _tok['at'] = time.time()
    return _tok['v']


def has_tag(eid, wid):
    for _ in range(4):
        try:
            r = json.loads(urllib.request.urlopen(
                'https://qyapi.weixin.qq.com/cgi-bin/externalcontact/get?external_userid=' + eid
                + '&access_token=' + token(), timeout=30).read())
        except Exception as e:
            log('  网络异常 %s: %s' % (eid[-8:], e))
            time.sleep(3)
            continue
        ec = r.get('errcode')
        if ec in (42001, 40014):
            token(force=True)
            continue
        if ec == 45009:
            time.sleep(30)
            continue
        if ec == -1:
            time.sleep(2)
            continue
        if ec not in (0, None):
            return 'ERR%d' % ec
        ids = [tg.get('tag_id') for fu in r.get('follow_user', []) for tg in fu.get('tags', [])]
        return 'OK' if wid in ids else 'MISSING'
    return 'RETRY_FAIL'


rows = []
for l in open(BACKFILL, encoding='utf-8'):
    p = l.rstrip('\n').split('\t')
    if len(p) >= 3 and p[1] == 'OK':
        rows.append((p[0], p[2]))

log('=== 复核开始 %s  共 %d 人 ===' % (time.strftime('%F %T'), len(rows)))
cnt = {}
bad = []
n = 0
for eid, wid in rows:
    st = has_tag(eid, wid)
    cnt[st] = cnt.get(st, 0) + 1
    if st != 'OK':
        bad.append((eid, st))
    n += 1
    if n % 200 == 0:
        log('  进度 %d/%d' % (n, len(rows)))
    time.sleep(0.46)
    if n % 100 == 0:
        time.sleep(10)

log('=== 复核汇总 ===')
for k in sorted(cnt, key=lambda x: -cnt[x]):
    log('  %-12s %d' % (k, cnt[k]))
for eid, st in bad[:20]:
    log('  异常: %s  %s' % (eid[-10:], st))
log('=== 复核结束 %s ===' % time.strftime('%F %T'))
