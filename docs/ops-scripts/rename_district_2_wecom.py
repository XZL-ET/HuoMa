# -*- coding: utf-8 -*-
"""县区改名 · 第 2 步（企微侧）：把 5 个错误县区标签的客户换成对应"店名（= 市名）"标签。

五组映射（源名在「县区」组，目标名也在「县区」组 —— 千万别打到同名的「市州」组）：
    城关区 → 兰州市      甘州区 → 张掖市      秦州区 → 天水市
    武都区 → 陇南市      西峰区 → 庆阳市

做法（先加后删，省一半调用）：
    1) 给身上带「源」标签的客户 mark_tag 加上「目标」标签（add-only，幂等）；
    2) 复核：重新全量枚举，确认"带源标签的客户"同时也带上了目标标签；
    3) 全量删掉企微里的「源」标签（del_corp_tag 自动把该标签从所有客户身上摘掉）。
    净效果：客户身上由「源」换成「目标」，无需逐客户 remove_tag。

子命令（依次跑）：
    python3 rename_district_2_wecom.py check     # 零写入。核对 5 组 wid/分组/同名市州干扰项 + 本地计数
    python3 rename_district_2_wecom.py census    # 只读。遍历成员，产出带源标签的客户名单
    python3 rename_district_2_wecom.py retag  --go  # 给名单客户加目标标签
    python3 rename_district_2_wecom.py verify       # 只读。复核源客户是否都已带上目标（delete 的前置门禁）
    python3 rename_district_2_wecom.py delete --go  # 删 5 个源标签；verify 有缺口则该源标签跳过不删
    python3 rename_district_2_wecom.py unretag --go # 兜底：delete 报 81011 删不掉的源标签，改逐客户摘除

为什么 delete 前必须 verify：del_corp_tag 会把源标签从**所有**客户身上摘掉。若某客户带源标签
却因「仅与不在 get_follow_user_list 里的成员有好友关系」等原因没被 census 枚举到，就没被补标，
删源标签会让其**丢掉店标签**。verify 全量复枚举后，只要有任何"带源不带目标"的客户，就拒绝删除。

企微限制：标签只能由**创建它的那个应用**删除。火马 secret 能删自己建的（秦州区/武都区/西峰区），
但 城关区/甘州区 由别处应用创建 → del_corp_tag 报 81011 no priviledge。甘州区可用 企微平台
（QiWeiPingTai）的 CORPSECRET 删；城关区三个 secret 都不行 → 只能：①让客户侧不带头（unretag，
逐客户 mark_tag remove_tag），②标签本身到 企业微信管理后台 手工删。

产物（行级即时落盘，可断点续跑）：
    /tmp/rename_district_census.tsv   eid \\t src_wid \\t userid
    /tmp/rename_district_retag.tsv    eid \\t src_wid \\t status \\t userid \\t errmsg
    /tmp/rename_district_verify.tsv   eid \\t src_wid \\t OK|MISSING
    /tmp/rename_district_2.log

整套顺序（三步，必须按序）：
    1. rename_district_1_region.sql   改 qr_code/school/qr_code_group 的 region_district
    2. 本脚本 check → census → retag --go → verify → delete --go
    3. rename_district_3_cleanup.sql  本地 tag 改挂 parent_id + 重接 customer_tag + 删源标签行
       （必须在 delete 之后跑，否则企微标签同步会把删掉的本地行按 wid 复活）
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

LOG = '/tmp/rename_district_2.log'
CENSUS = '/tmp/rename_district_census.tsv'
RETAG = '/tmp/rename_district_retag.tsv'
VERIFY = '/tmp/rename_district_verify.tsv'
UNRETAG = '/tmp/rename_district_unretag.tsv'

# src=错误县区名, dst=正确店名(=市名)；三个 wid 为 2026-10-13 线上核验值，check 会再次校验。
#  src_wid=「县区」组源标签; dst_wid=「县区」组目标标签; city_wid=「市州」组同名干扰标签(不许动/不许用)。
PAIRS = [
    {'src': '城关区', 'dst': '兰州市',
     'src_wid': 'etZUH8SgAAYY6fofVzxOGZ39uKn38V_w',
     'dst_wid': 'etZUH8SgAAFGIN0g4s6cmNXJjfLcq9Fg',
     'city_wid': 'etZUH8SgAAQzFeVJ4clFbDLdujb0_mJQ'},
    {'src': '甘州区', 'dst': '张掖市',
     'src_wid': 'etZUH8SgAAjDFdJ0l7QaXcqTimCre9rQ',
     'dst_wid': 'etZUH8SgAAb7XArI5R_WJpGIZKBzooNg',
     'city_wid': 'etZUH8SgAAgaxHHBVWx_TCS3pORaJrKg'},
    {'src': '秦州区', 'dst': '天水市',
     'src_wid': 'etZUH8SgAAmcAjtn0hFxBELzk1CrDHew',
     'dst_wid': 'etZUH8SgAA32AwppUdR-h-qBTKBfJ40g',
     'city_wid': 'etZUH8SgAAHmOLBrrLtQUvXfg1rd3zKQ'},
    {'src': '武都区', 'dst': '陇南市',
     'src_wid': 'etZUH8SgAAwNLHhxbGHR7JXOF2bHNcQg',
     'dst_wid': 'etZUH8SgAA9n2ejSPPrrNg9QBGsinlMw',
     'city_wid': 'etZUH8SgAAf3XV_4phj13EHfvgY0_93Q'},
    {'src': '西峰区', 'dst': '庆阳市',
     'src_wid': 'etZUH8SgAA1F38tY4CPuVOS26_3-kk3A',
     'dst_wid': 'etZUH8SgAA4pNFhsNWkmJQzHMcifAHKA',
     'city_wid': 'etZUH8SgAAdBnJgRwVedpj76zGhAIEbw'},
]
BY_SRC_WID = {p['src_wid']: p for p in PAIRS}
SRC_WIDS = set(BY_SRC_WID)
DST_WIDS = {p['dst_wid'] for p in PAIRS}
RELEVANT = SRC_WIDS | DST_WIDS

GO = '--go' in sys.argv
CMD = next((a for a in sys.argv[1:] if not a.startswith('-')), 'check')

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


def sql(q):
    r = subprocess.run(['mysql', '-h', env['DB_HOST'], '-u', env['DB_USERNAME'],
                        '-p' + env['DB_PASSWORD'], '-N', '-B', '-e', q, 'bookstore_qrcode'],
                       capture_output=True, text=True)
    return r.stdout.strip().splitlines()


_tok = {'v': None, 'at': 0}


def token(force=False):
    if force or not _tok['v'] or time.time() - _tok['at'] > 6000:
        d = json.loads(urllib.request.urlopen(
            'https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=' + env['WECOM_CORP_ID']
            + '&corpsecret=' + env['WECOM_CORP_SECRET'], timeout=20).read())
        _tok['v'] = d['access_token']
        _tok['at'] = time.time()
    return _tok['v']


def api(path, body=None, retries=6):
    """GET(无 body) / POST(有 body)。带 token 刷新、限频退避、瞬时重试。"""
    for _ in range(retries):
        try:
            if body is None:
                url = ('https://qyapi.weixin.qq.com/cgi-bin/' + path
                       + ('&' if '?' in path else '?') + 'access_token=' + token())
                r = json.loads(urllib.request.urlopen(url, timeout=30).read())
            else:
                req = urllib.request.Request(
                    'https://qyapi.weixin.qq.com/cgi-bin/' + path + '?access_token=' + token(),
                    data=json.dumps(body).encode('utf-8'),
                    headers={'Content-Type': 'application/json'})
                r = json.loads(urllib.request.urlopen(req, timeout=30).read())
        except Exception as e:
            log('  网络异常: %s' % e)
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
        return r
    return {'errcode': -1, 'errmsg': 'retries-exhausted'}


def wid_index():
    """返回 wid -> (group_name, tag_name)。"""
    d = api('externalcontact/get_corp_tag_list?')
    idx = {}
    for g in d.get('tag_group', []):
        for t in g.get('tag', []):
            idx[t['id']] = (g.get('group_name', ''), t.get('name', ''))
    return idx


def iter_all():
    """遍历 corp 全部成员的全部客户，yield (eid, userid, [tag_id...])。"""
    d = api('externalcontact/get_follow_user_list?')
    members = d.get('follow_user', [])
    log('成员数 %d' % len(members))
    for i, uid in enumerate(members):
        cursor = ''
        while True:
            r = api('externalcontact/batch/get_by_user', {'userid': uid, 'cursor': cursor, 'limit': 100})
            if r.get('errcode') not in (0, None):
                log('  batch 失败 uid=%s err=%s' % (uid, r.get('errcode')))
                break
            for item in r.get('external_contact_list', []):
                eid = item.get('external_contact', {}).get('external_userid')
                fi = item.get('follow_info', {})
                yield eid, fi.get('userid', ''), (fi.get('tag_id') or [])
            cursor = r.get('next_cursor', '')
            if not cursor:
                break
            time.sleep(0.2)
        if (i + 1) % 100 == 0:
            log('  成员进度 %d/%d' % (i + 1, len(members)))
        time.sleep(0.15)


# ---------------------------------------------------------------------------
def cmd_check():
    log('=== check %s ===' % time.strftime('%F %T'))
    idx = wid_index()
    if not idx:
        log('!! 取不到企微标签列表，中止')
        return
    ok = True
    for p in PAIRS:
        s, d, c = idx.get(p['src_wid']), idx.get(p['dst_wid']), idx.get(p['city_wid'])
        log('  [%s → %s]' % (p['src'], p['dst']))
        log('     源   %s  %s' % (p['src_wid'], s))
        log('     目标 %s  %s' % (p['dst_wid'], d))
        log('     市州干扰 %s  %s' % (p['city_wid'], c))
        if not s or s[0] != '县区' or s[1] != p['src']:
            log('     !! 源 wid 不在「县区」组或名不符，停'); ok = False
        if not d or d[0] != '县区' or d[1] != p['dst']:
            log('     !! 目标 wid 不在「县区」组或名不符，停'); ok = False
        if not c or c[0] != '市州' or c[1] != p['dst']:
            log('     !! 市州干扰 wid 不在「市州」组或名不符，停'); ok = False
        if p['src_wid'] == p['dst_wid']:
            log('     !! 源/目标 wid 相同，停'); ok = False
        if p['dst_wid'] == p['city_wid']:
            log('     !! 目标 wid 撞上「市州」同名 wid —— 会把店标签打错组，停'); ok = False
    log('  ---- 本地计数 ----')
    rows = sql("SELECT t.name, t.group_keyword, COUNT(ct.id) FROM tag t "
               "LEFT JOIN customer_tag ct ON ct.tag_id=t.id "
               "WHERE t.name IN ('城关区','甘州区','秦州区','武都区','西峰区') "
               "GROUP BY t.name, t.group_keyword ORDER BY t.name, t.group_keyword")
    tot = 0
    for r in rows:
        p = r.split('\t')
        log('     tag[%s gk=%r] 客户 %s' % (p[0], p[1], p[2]))
        tot += int(p[2])
    log('  源标签本地绑定合计 %d' % tot)
    log('  结果：%s' % ('全部通过 ✔（可继续 census）' if ok else '有断言失败 ✘ —— 先修再跑'))
    log('=== check 结束 ===')


def cmd_census():
    log('=== census %s（全量遍历成员）===' % time.strftime('%F %T'))
    done = set()
    if os.path.exists(CENSUS):
        for l in open(CENSUS, encoding='utf-8'):
            if l.strip():
                done.add(l.split('\t')[0] + '|' + l.split('\t')[1])
    log('已存在 census 行 %d（断点续跑）' % len(done))

    n_new = 0
    with open(CENSUS, 'a', encoding='utf-8') as out:
        for eid, uid, tags in iter_all():
            for w in tags:
                if w in SRC_WIDS:
                    key = eid + '|' + w
                    if key in done:
                        continue
                    out.write('%s\t%s\t%s\n' % (eid, w, uid)); out.flush()
                    done.add(key); n_new += 1

    cnt = {}
    for l in open(CENSUS, encoding='utf-8'):
        if l.strip():
            w = l.split('\t')[1]
            cnt[w] = cnt.get(w, 0) + 1
    log('=== census 汇总（按源标签）===')
    for p in PAIRS:
        log('  %-6s → %-6s  %d 客户' % (p['src'], p['dst'], cnt.get(p['src_wid'], 0)))
    log('=== census 结束 %s ===' % time.strftime('%F %T'))


def cmd_retag():
    if not GO:
        log('retag 未加 --go，dry-run 列出前 20 条：')
        n = 0
        for l in open(CENSUS, encoding='utf-8'):
            p = l.rstrip('\n').split('\t')
            if len(p) >= 3 and p[1] in BY_SRC_WID:
                log('  [dry] %s  %s→%s  userid=%s' % (p[0][-8:], BY_SRC_WID[p[1]]['src'],
                                                     BY_SRC_WID[p[1]]['dst'], p[2]))
                n += 1
                if n >= 20:
                    break
        log('（加 --go 才真打）')
        return

    done = set()
    if os.path.exists(RETAG):
        for l in open(RETAG, encoding='utf-8'):
            if l.strip() and l.split('\t')[2] == 'OK':
                done.add(l.split('\t')[0] + '|' + l.split('\t')[1])
    rows = []
    for l in open(CENSUS, encoding='utf-8'):
        p = l.rstrip('\n').split('\t')
        if len(p) >= 3 and p[1] in BY_SRC_WID and (p[0] + '|' + p[1]) not in done:
            rows.append((p[0], p[1], p[2]))
    log('=== retag GO %s 待打 %d（已 OK %d）===' % (time.strftime('%F %T'), len(rows), len(done)))

    n = 0
    for eid, src_wid, uid in rows:
        dst_wid = BY_SRC_WID[src_wid]['dst_wid']
        r = api('externalcontact/mark_tag', {'userid': uid, 'external_userid': eid, 'add_tag': [dst_wid]})
        status = 'OK' if r.get('errcode') in (0, None) else 'ERR%s' % r.get('errcode')
        with open(RETAG, 'a', encoding='utf-8') as f:
            f.write('%s\t%s\t%s\t%s\t%s\n' % (eid, src_wid, status, uid, r.get('errmsg', '')))
        n += 1
        if n % 100 == 0:
            log('  进度 %d/%d' % (n, len(rows)))
        time.sleep(0.35)

    cnt, fail = {}, {}
    for l in open(RETAG, encoding='utf-8'):
        if l.strip():
            p = l.split('\t')
            cnt[p[2]] = cnt.get(p[2], 0) + 1
            if p[2] != 'OK':
                fail[p[1]] = fail.get(p[1], 0) + 1
    log('=== retag 结果 ===')
    for k in sorted(cnt, key=lambda x: -cnt[x]):
        log('  %-10s %d' % (k, cnt[k]))
    for w, c in fail.items():
        log('  !! %s(%s) 失败 %d' % (BY_SRC_WID[w]['src'], w, c))


def cmd_verify():
    """只读全量复枚举：凡带源标签的客户，必须同时带对应目标标签，否则记 MISSING。"""
    log('=== verify %s（全量复枚举）===' % time.strftime('%F %T'))
    seen = {}  # eid -> 该客户身上出现的 relevant wids（跨成员并集）
    for eid, uid, tags in iter_all():
        rel = [w for w in tags if w in RELEVANT]
        if rel:
            seen.setdefault(eid, set()).update(rel)

    stat = {}
    with open(VERIFY, 'w', encoding='utf-8') as out:
        for eid, ws in seen.items():
            for sw in (ws & SRC_WIDS):
                p = BY_SRC_WID[sw]
                ok = p['dst_wid'] in ws
                out.write('%s\t%s\t%s\n' % (eid, sw, 'OK' if ok else 'MISSING'))
                d = stat.setdefault(sw, {'OK': 0, 'MISSING': 0})
                d['OK' if ok else 'MISSING'] += 1

    log('=== verify 汇总（源客户是否都已带目标）===')
    total_missing = 0
    for p in PAIRS:
        d = stat.get(p['src_wid'], {'OK': 0, 'MISSING': 0})
        total_missing += d['MISSING']
        log('  %-6s → %-6s  已带目标 %d / 缺目标 %d' % (p['src'], p['dst'], d['OK'], d['MISSING']))
    log('  MISSING 合计 %d  %s' % (total_missing,
        '（可 delete）' if total_missing == 0 else '（不可删！先重跑 retag 再 verify）'))
    log('=== verify 结束 %s ===' % time.strftime('%F %T'))


def cmd_delete():
    if not GO:
        log('delete 未加 --go。将删除这 5 个「县区」源标签：')
        for p in PAIRS:
            log('  %-6s %s' % (p['src'], p['src_wid']))
        log('（加 --go 才真删；删除会把该标签从所有客户身上自动摘掉）')
        return

    # 门禁 1：必须先跑 verify
    if not os.path.exists(VERIFY):
        log('!! 没有 %s —— 先跑 verify 再删' % VERIFY)
        return
    miss = {}
    for l in open(VERIFY, encoding='utf-8'):
        if l.strip():
            p = l.split('\t')
            if p[2].strip() != 'OK':
                miss[p[1]] = miss.get(p[1], 0) + 1

    # 门禁 2：retag 失败也不能删
    fail = {}
    if os.path.exists(RETAG):
        for l in open(RETAG, encoding='utf-8'):
            if l.strip() and l.split('\t')[2].strip() != 'OK':
                fail[l.split('\t')[1]] = fail.get(l.split('\t')[1], 0) + 1

    log('=== delete GO %s ===' % time.strftime('%F %T'))
    for p in PAIRS:
        if miss.get(p['src_wid'], 0) > 0:
            log('  SKIP %s —— verify 有 %d 个"带源不带目标"客户，删了会丢店标签' % (p['src'], miss[p['src_wid']]))
            continue
        if fail.get(p['src_wid'], 0) > 0:
            log('  SKIP %s —— 有 %d 条加标失败，先重跑 retag' % (p['src'], fail[p['src_wid']]))
            continue
        if p['src_wid'] == p['dst_wid'] or p['src_wid'] == p['city_wid']:
            log('  SKIP %s —— wid 护栏异常' % p['src'])
            continue
        r = api('externalcontact/del_corp_tag', {'tag_id': [p['src_wid']]})
        log('  DEL  %-6s %s -> errcode=%s %s' % (p['src'], p['src_wid'], r.get('errcode'), r.get('errmsg', '')))
        time.sleep(0.3)

    idx = wid_index()
    log('=== 复查（源应消失，目标应仍在）===')
    for p in PAIRS:
        log('  源 %-6s 仍在？%s    目标 %-6s 仍在？%s'
            % (p['src'], p['src_wid'] in idx, p['dst'], p['dst_wid'] in idx))


def cmd_unretag():
    """兜底：del_corp_tag 删不掉的源标签（81011 非本应用创建），改逐客户 remove_tag 摘掉。

    只对「企微里仍存在」的源 wid 动手（其余已被 del_corp_tag 连带摘除）。
    """
    idx = wid_index()
    alive = [p for p in PAIRS if p['src_wid'] in idx]
    if not GO:
        log('unretag 未加 --go。企微里仍存在的源标签（需逐客户摘除）：')
        cnt = {}
        for l in open(CENSUS, encoding='utf-8') if os.path.exists(CENSUS) else []:
            if l.strip():
                cnt[l.split('\t')[1]] = cnt.get(l.split('\t')[1], 0) + 1
        for p in alive:
            log('  %-6s %s（census %d 客户）' % (p['src'], p['src_wid'], cnt.get(p['src_wid'], 0)))
        if not alive:
            log('  （无 —— 源标签已全部从企微消失，不需 unretag）')
        log('（加 --go 才真摘；摘除后客户身上只剩目标标签）')
        return

    if not alive:
        log('=== unretag %s：企微里源标签已全部消失，无需摘除 ===' % time.strftime('%F %T'))
        return
    alive_wids = {p['src_wid'] for p in alive}

    done = set()
    if os.path.exists(UNRETAG):
        for l in open(UNRETAG, encoding='utf-8'):
            if l.strip() and l.split('\t')[2].strip() == 'OK':
                done.add(l.split('\t')[0] + '|' + l.split('\t')[1])
    todo = []
    for l in open(CENSUS, encoding='utf-8'):
        if not l.strip():
            continue
        p = l.rstrip('\n').split('\t')
        if len(p) >= 3 and p[1] in alive_wids and (p[0] + '|' + p[1]) not in done:
            todo.append((p[0], p[1], p[2]))
    log('=== unretag GO %s 待摘 %d（已 OK %d）===' % (time.strftime('%F %T'), len(todo), len(done)))

    n = 0
    for eid, src_wid, uid in todo:
        r = api('externalcontact/mark_tag', {'userid': uid, 'external_userid': eid, 'remove_tag': [src_wid]})
        status = 'OK' if r.get('errcode') in (0, None) else 'ERR%s' % r.get('errcode')
        with open(UNRETAG, 'a', encoding='utf-8') as f:
            f.write('%s\t%s\t%s\t%s\t%s\n' % (eid, src_wid, status, uid, r.get('errmsg', '')))
        n += 1
        if n % 100 == 0:
            log('  进度 %d/%d' % (n, len(todo)))
        time.sleep(0.35)

    cnt = {}
    for l in open(UNRETAG, encoding='utf-8'):
        if l.strip():
            y = l.split('\t')
            cnt[y[2]] = cnt.get(y[2], 0) + 1
    log('=== unretag 结果 ===')
    for k in sorted(cnt, key=lambda x: -cnt[x]):
        log('  %-10s %d' % (k, cnt[k]))


def cmd_unretag_all():
    """unretag 的彻底版：逐（成员,客户）枚举，把仍存在的源标签从**每个**关注关系上摘掉。

    unretag 只处理 census 里记的那一个成员；标签是按 (userid, external_userid) 存的，
    同一客户可能被多个成员同时打了源标签 —— 那条 census 记录只记了第一个成员，会漏。
    （被 del_corp_tag 删掉的 4 个源标签不受影响：删标签组会从所有关注关系上摘掉。）
    """
    idx = wid_index()
    alive = [p for p in PAIRS if p['src_wid'] in idx]
    if not alive:
        log('=== unretag-all %s：企微里源标签已全部消失，无需处理 ===' % time.strftime('%F %T'))
        return
    alive_wids = {p['src_wid'] for p in alive}
    if not GO:
        log('unretag-all 未加 --go。企微里仍存在的源标签：%s'
            % ', '.join('%s(%s)' % (p['src'], p['src_wid']) for p in alive))
        log('（加 --go 才真跑：全量枚举成员 → 对每个仍带源标签的关注关系 remove_tag）')
        return

    out = '/tmp/rename_district_unretag_all.tsv'
    done = set()
    if os.path.exists(out):
        for l in open(out, encoding='utf-8'):
            if l.strip() and l.split('\t')[3].strip() == 'OK':
                p = l.split('\t')
                done.add(p[0] + '|' + p[2] + '|' + p[1])  # eid|uid|wid
    log('=== unretag-all GO %s（全量枚举）===' % time.strftime('%F %T'))
    n, cnt = 0, {}
    for eid, uid, tags in iter_all():
        for w in tags:
            if w in alive_wids and (eid + '|' + uid + '|' + w) not in done:
                r = api('externalcontact/mark_tag', {'userid': uid, 'external_userid': eid, 'remove_tag': [w]})
                st = 'OK' if r.get('errcode') in (0, None) else 'ERR%s' % r.get('errcode')
                with open(out, 'a', encoding='utf-8') as f:
                    f.write('%s\t%s\t%s\t%s\t%s\n' % (eid, w, uid, st, r.get('errmsg', '')))
                done.add(eid + '|' + uid + '|' + w)
                cnt[st] = cnt.get(st, 0) + 1
                n += 1
                if n % 100 == 0:
                    log('  已摘 %d' % n)
                time.sleep(0.35)
    log('=== unretag-all 结果（本次摘除 %d）===' % n)
    for k in sorted(cnt, key=lambda x: -cnt[x]):
        log('  %-10s %d' % (k, cnt[k]))


if __name__ == '__main__':
    {'check': cmd_check, 'census': cmd_census, 'retag': cmd_retag,
     'verify': cmd_verify, 'delete': cmd_delete,
     'unretag': cmd_unretag, 'unretag-all': cmd_unretag_all}.get(CMD, cmd_check)()
