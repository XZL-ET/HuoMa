# -*- coding: utf-8 -*-
"""R1 补打：把普查出的 NO_GRADE 客户补上其 target_wecom_tag_id（「年级」组活标签）。

输入：/tmp/r1_census_lines.tsv（普查产出，eid \\t cls \\t userid \\t target_wid \\t 现有年级名）
只处理 cls == NO_GRADE 的行。默认 dry-run，打印将执行的动作；加 --go 才真调 mark_tag。

输出（行级即时落盘，可断点续跑；已 OK 的 eid 会跳过）：
  /tmp/r1_backfill_result.tsv   eid \\t status \\t wid \\t userid \\t errmsg
  /tmp/r1_backfill.log
"""
import json
import os
import sys
import time
import urllib.request

CENSUS = '/tmp/r1_census_lines.tsv'
OUT = '/tmp/r1_backfill_result.tsv'
LOG = '/tmp/r1_backfill.log'
GO = '--go' in sys.argv

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


def mark_tag(eid, uid, wid):
    """返回 (errcode, errmsg)。带 token 刷新与限频退避。"""
    body = json.dumps({'userid': uid, 'external_userid': eid, 'add_tag': [wid]}).encode('utf-8')
    for attempt in range(5):
        req = urllib.request.Request(
            'https://qyapi.weixin.qq.com/cgi-bin/externalcontact/mark_tag?access_token=' + token(),
            data=body, headers={'Content-Type': 'application/json'})
        try:
            r = json.loads(urllib.request.urlopen(req, timeout=30).read())
        except Exception as e:
            log('  网络异常 %s: %s' % (eid[-8:], e))
            time.sleep(3)
            continue
        ec = r.get('errcode')
        if ec in (42001, 40014):
            token(force=True)
            continue
        if ec == 45009:
            log('  45009 限频，退避 30s')
            time.sleep(30)
            continue
        if ec == -1:
            time.sleep(2)
            continue
        return ec, r.get('errmsg', '')
    return -1, 'retries-exhausted'


def main():
    rows = []
    for l in open(CENSUS, encoding='utf-8'):
        p = l.rstrip('\n').split('\t')
        if len(p) >= 4 and p[1] == 'NO_GRADE' and p[2] and p[3]:
            rows.append((p[0], p[2], p[3]))

    done = set()
    if os.path.exists(OUT):
        for l in open(OUT, encoding='utf-8'):
            if l.strip() and l.split('\t')[1] == 'OK':
                done.add(l.split('\t')[0])
    todo = [r for r in rows if r[0] not in done]

    log('=== R1 补打 %s  模式=%s  名单=%d  已完成=%d  待执行=%d ==='
        % (time.strftime('%F %T'), 'GO' if GO else 'dry-run', len(rows), len(done), len(todo)))

    n = 0
    for eid, uid, wid in todo:
        if not GO:
            log('  [dry-run] %s  userid=%s  add_tag=%s' % (eid[-8:], uid, wid))
            n += 1
            if n >= 20:
                log('  （dry-run 只列前 20 条，共 %d）' % len(todo))
                break
            continue
        ec, msg = mark_tag(eid, uid, wid)
        status = 'OK' if ec in (0, None) else 'ERR%d' % ec
        with open(OUT, 'a', encoding='utf-8') as f:
            f.write('%s\t%s\t%s\t%s\t%s\n' % (eid, status, wid, uid, msg))
        n += 1
        if n % 100 == 0:
            log('  进度 %d/%d' % (n, len(todo)))
        time.sleep(0.3)

    if GO:
        cnt = {}
        for l in open(OUT, encoding='utf-8'):
            if l.strip():
                c = l.split('\t')[1]
                cnt[c] = cnt.get(c, 0) + 1
        log('=== 结果汇总 ===')
        for k in sorted(cnt, key=lambda x: -cnt[x]):
            log('  %-10s %d' % (k, cnt[k]))
    log('=== R1 补打结束 %s ===' % time.strftime('%F %T'))


if __name__ == '__main__':
    main()
