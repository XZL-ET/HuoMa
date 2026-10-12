# -*- coding: utf-8 -*-
"""给 markFailed 里仍缺年级标签的客户补标：目标年级 = 本地行名的下一级（+1，学期升学规律）。

用法： python3 fix_mf.py        # dry-run
       python3 fix_mf.py --go   # 真打
"""
import json
import subprocess
import sys
import time
import urllib.request

GO = '--go' in sys.argv
LADDER = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级',
          '七年级', '八年级', '九年级', '高一', '高二', '高三']

env = {}
for line in open('/etc/systemd/system/huoma.env'):
    s = line.strip()
    if s and not s.startswith('#') and '=' in s:
        k, v = s.split('=', 1)
        env[k.strip()] = v.strip()

tok = json.loads(urllib.request.urlopen(
    'https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=' + env['WECOM_CORP_ID']
    + '&corpsecret=' + env['WECOM_CORP_SECRET'], timeout=20).read())['access_token']


def api_get(path):
    return json.loads(urllib.request.urlopen(
        'https://qyapi.weixin.qq.com/cgi-bin/' + path + '&access_token=' + tok, timeout=30).read())


def api_post(path, body):
    req = urllib.request.Request('https://qyapi.weixin.qq.com/cgi-bin/' + path + '?access_token=' + tok,
                                 data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())


def sql(q):
    r = subprocess.run(['mysql', '-h', env['DB_HOST'], '-u', env['DB_USERNAME'],
                        '-p' + env['DB_PASSWORD'], '-N', '-B', '-e', q, 'bookstore_qrcode'],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print('SQL ERR ' + r.stderr.strip()[:200])
    return r.stdout.strip().splitlines()


d = api_get('externalcontact/get_corp_tag_list?')
wid2, grade_by_name = {}, {}
for g in d.get('tag_group', []):
    gn = g.get('group_name', '')
    for t in g.get('tag', []):
        wid2[t['id']] = (gn, t.get('name', ''))
        if gn == '年级':
            grade_by_name.setdefault(t.get('name', ''), t['id'])
GRADE_WIDS = {w for w, (gn, _) in wid2.items() if gn == '年级'}

rows = sql("SELECT p.external_userid, p.local_tag_id, t.name, IFNULL(t.group_keyword,'') "
           "FROM grade_tag_migration_progress p JOIN tag t ON t.id=p.local_tag_id "
           "WHERE p.action='WECOM_MARK_FAILED'")
print('markFailed 共 %d 条；模式=%s' % (len(rows), '实打' if GO else 'DRY-RUN'))

done = skip = failed = 0
for line in rows:
    p = line.split('\t')
    if len(p) < 3:
        print('  【行解析失败，字段不足】%r' % line)
        failed += 1
        continue
    eid, rid, rname = p[0], p[1], p[2]
    idx = LADDER.index(rname) if rname in LADDER else -1
    if idx < 0 or idx + 1 >= len(LADDER):
        print('  行#%s 名「%s」不在年级阶梯里，跳过' % (rid, rname))
        failed += 1
        continue
    tgt = grade_by_name[LADDER[idx + 1]]
    r = api_get('externalcontact/get?external_userid=' + eid)
    if r.get('errcode') not in (0, None):
        print('  %s 查询失败 %s' % (eid[-8:], r.get('errcode')))
        failed += 1
        continue
    already = any(tg.get('tag_id') in GRADE_WIDS for fu in r.get('follow_user', [])
                  for tg in fu.get('tags', []))
    if already:
        skip += 1
        continue
    userid = None
    for fu in r.get('follow_user', []):
        userid = fu.get('userid')
        break
    if not GO:
        print('  %s 行#%s(%s) → 补 年级/%s (userid=%s)' % (eid[-8:], rid, rname, LADDER[idx + 1], userid))
        done += 1
        continue
    res = api_post('externalcontact/mark_tag',
                   {'userid': userid, 'external_userid': eid, 'add_tag': [tgt]})
    ok = res.get('errcode') in (0, None)
    time.sleep(0.2)
    chk = api_get('externalcontact/get?external_userid=' + eid)
    got = any(tg.get('tag_id') == tgt for f in chk.get('follow_user', []) for tg in f.get('tags', []))
    print('  %s 行#%s(%s) → 年级/%s  markTag=%s 复核=%s'
          % (eid[-8:], rid, rname, LADDER[idx + 1],
             'OK' if ok else 'ERR%s %s' % (res.get('errcode'), res.get('errmsg')),
             '已带上' if got else '滞后/未带上'))
    done += 1

print()
print('小结：%s  补打=%d  跳过(已有年级组)=%d  失败=%d' % ('实打' if GO else 'DRY-RUN', done, skip, failed))
