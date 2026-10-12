# -*- coding: utf-8 -*-
"""给「删标签会丢年级」的客户补打「年级」组对应标签（markTag，只增不减，可逆）。

默认跳过落在裸 `学校` 组 wid（= Phase B 会被护栏 block 的 2 个）上的客户 —— 那些标签这轮不删。
用法：  python3 fix_11.py          # dry-run，只列清单
        python3 fix_11.py --go     # 真打
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
BLOCKED_BARE = {w for w, (gn, _) in DOOM.items() if gn == '学校'}

eids = [l.strip() for l in open(RISK) if l.strip()]
print('风险名单 %d 人；模式=%s' % (len(eids), '实打' if GO else 'DRY-RUN'))

fixed = skip_blocked = failed = already = 0
for eid in eids:
    r = api_get('externalcontact/get?external_userid=' + eid)
    if r.get('errcode') not in (0, None):
        print('  %s 查询失败 %s' % (eid[-8:], r.get('errcode')))
        failed += 1
        continue
    have_grade = False
    cands = []          # (userid, doom_wid, name)
    for fu in r.get('follow_user', []):
        for tg in fu.get('tags', []):
            w = tg.get('tag_id')
            if w in {x for x, (gn, _) in wid2.items() if gn == '年级'}:
                have_grade = True
            if w in DOOM:
                cands.append((fu.get('userid'), w, DOOM[w][1]))
    if have_grade:
        already += 1
        continue
    if not cands:
        print('  %s 身上已无待删老标签（可能被人改过）' % eid[-8:])
        failed += 1
        continue
    userid, doom_wid, gname = cands[0]
    if doom_wid in BLOCKED_BARE:
        skip_blocked += 1
        continue
    tgt = grade_by_name.get(gname)
    if not tgt:
        print('  %s 老标签名「%s」在年级组无同名标签，跳过' % (eid[-8:], gname))
        failed += 1
        continue
    if not GO:
        print('  %s  老标签[%s]  →  补打 年级/%s  用 userid=%s'
              % (eid[-8:], gname, gname, userid))
        fixed += 1
        continue
    res = api_post('externalcontact/mark_tag',
                   {'userid': userid, 'external_userid': eid, 'add_tag': [tgt]})
    ok = res.get('errcode') in (0, None)
    time.sleep(0.1)
    # 复核
    chk = api_get('externalcontact/get?external_userid=' + eid)
    got = any(tg.get('tag_id') == tgt for fu in chk.get('follow_user', []) for tg in fu.get('tags', []))
    print('  %s  [%s] → 年级/%s  markTag=%s 复核=%s'
          % (eid[-8:], gname, gname, 'OK' if ok else ('ERR%s %s' % (res.get('errcode'), res.get('errmsg'))),
             '已带上' if got else '仍未带上'))
    fixed += 1 if got else 0
    failed += 0 if got else 1

print()
print('小结：%s  补打成功=%d  跳过(裸学校组wid，这轮不删)=%d  已带年级组=%d  失败=%d'
      % ('实打' if GO else 'DRY-RUN', fixed, skip_blocked, already, failed))
