# -*- coding: utf-8 -*-
"""给全部「删标签会丢年级」的客户补打「年级」组同名标签（含裸 `学校` 组那 118 人）。
按客户身上所有学校组年级标签名去重后逐个补打；只增不减，可逆。

用法： python3 fix_all.py        # dry-run
       python3 fix_all.py --go   # 真打
"""
import json
import sys
import time
import urllib.request

GO = '--go' in sys.argv
RISK = '/tmp/census_risk_eids.txt'
GRADES = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级',
          '七年级', '八年级', '九年级', '高一', '高二', '高三']
CORE = {'年级', '班级', '市州', '县区', '渠道来源'}

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


d = api_get('externalcontact/get_corp_tag_list?')
wid2, grade_by_name = {}, {}
for g in d.get('tag_group', []):
    gn = g.get('group_name', '')
    for t in g.get('tag', []):
        wid2[t['id']] = (gn, t.get('name', ''))
        if gn == '年级':
            grade_by_name.setdefault(t.get('name', ''), t['id'])
DOOM = {w: (gn, tn) for w, (gn, tn) in wid2.items() if gn not in CORE and tn in GRADES}
GRADE_WIDS = {w for w, (gn, _) in wid2.items() if gn == '年级'}

eids = [l.strip() for l in open(RISK) if l.strip()]
print('风险名单 %d 人；模式=%s' % (len(eids), '实打' if GO else 'DRY-RUN'))

done = already = failed = 0
for eid in eids:
    r = api_get('externalcontact/get?external_userid=' + eid)
    if r.get('errcode') not in (0, None):
        print('  %s 查询失败 %s' % (eid[-8:], r.get('errcode')))
        failed += 1
        continue
    have = set()
    need = {}          # 年级名 -> 用来 markTag 的 userid
    for fu in r.get('follow_user', []):
        for tg in fu.get('tags', []):
            w = tg.get('tag_id')
            if w in GRADE_WIDS:
                have.add(w)
            if w in DOOM and DOOM[w][1] in grade_by_name:
                need.setdefault(DOOM[w][1], fu.get('userid'))
    todo = {n: u for n, u in need.items() if grade_by_name[n] not in have}
    if not todo:
        already += 1
        continue
    for name, userid in todo.items():
        tgt = grade_by_name[name]
        if not GO:
            print('  %s  → 补打 年级/%s (userid=%s)' % (eid[-8:], name, userid))
            done += 1
            continue
        res = api_post('externalcontact/mark_tag',
                       {'userid': userid, 'external_userid': eid, 'add_tag': [tgt]})
        ok = res.get('errcode') in (0, None)
        time.sleep(0.15)
        chk = api_get('externalcontact/get?external_userid=' + eid)
        got = any(tg.get('tag_id') == tgt for f in chk.get('follow_user', []) for tg in f.get('tags', []))
        print('  %s  → 年级/%s  markTag=%s 复核=%s'
              % (eid[-8:], name, 'OK' if ok else ('ERR%s %s' % (res.get('errcode'), res.get('errmsg'))),
                 '已带上' if got else '滞后/未带上'))
        done += 1

print()
print('小结：%s  补打=%d  已带年级组=%d  失败=%d' % ('实打' if GO else 'DRY-RUN', done, already, failed))
