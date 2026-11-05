# -*- coding: utf-8 -*-
"""§4-2 县级学校组并入市组（企微侧）

    学校-榆中县 → 学校-兰州市     （榆中县属兰州市）
    学校-民勤县 → 学校-武威市     （民勤县属武威市）

企微**没有"把标签挪到另一个组"的 API**：移动 = 在目标组新建/采用同名标签 + 给原客户补标 + 删旧标签。
六项全部 MOVE（census 2026-10-15 实测客户数，本地镜像不可信）：

    学校-榆中县 → 学校-兰州市
      榆中县朝阳学校(834) 文成小学(1027) 博雅小学(0) 栖云小学(3)
      榆中县崇文实验学校(1096) ← 并入兰州市同名份(1112)
    学校-民勤县 → 学校-武威市
      民勤一中(156)          ← 并入武威市「民勤县第一中学」(135)

子命令（依次跑）：
    check         零写入。核 src wid/组/名、目标组是否存在、是否已撞同名、本地绑定计数
    probe-owner   归属探测：把 src 标签 edit 成"同名"（幂等）。非本应用创建会报 81011
    prepare --go  在目标组建 MOVE 项的新标签，落 wid 映射 /tmp/merge_county_target_map.tsv
    census        只读。遍历成员，产出带 src 标签的 (客户,成员) 名单（仅 MOVE 项）
    retag  --go   给名单客户 mark_tag 加目标标签（add-only，幂等）
    verify        只读。复核"带源不带目标"=0（delete 的门禁）
    delete --go   删 src 标签；verify 有缺口 / retag 有失败的项跳过；DROP_DUP 项不受门禁约束（0 客户）
    local  --go   本地镜像：MOVE 项把旧 wid 行绑定重接到新 dst 行；DROP_DUP 项删空旧行

产物：/tmp/merge_county_{target_map,census,retag,verify}.tsv  /tmp/merge_county.log

顺序：check → probe-owner → prepare → census → retag → verify → delete → local

注：探测实测 6 个 src 标签均 81011（非本应用创建），本应用的 delete/edit 会被企微拒。
    故 delete 一步须由**管理员在企微管理后台**手工删（客户联系→标签管理）；
    脚本的 delete 子命令仅在属主应用 secret 下才有效，否则整批 81011，属预期。
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

LOG = '/tmp/merge_county.log'
TARGET_MAP = '/tmp/merge_county_target_map.tsv'
CENSUS = '/tmp/merge_county_census.tsv'
RETAG = '/tmp/merge_county_retag.tsv'
VERIFY = '/tmp/merge_county_verify.tsv'

# src_wid 取自 2026-10-15 线上核验；check 会再校验。dst_name=目标组里同义标签名（MOVE 时=src_name）。
#
# 2026-10-15 修正：census 全量枚举显示后两项（崇文/民勤一中）**并非 0 客户**——本地镜像骗人。
#   崇文实验学校(榆中县组) 有 1096 客户，兰州市组已有同名份(1112) → 并入同名份。
#   民勤一中(民勤县组)     有 156 客户，武威市组已有「民勤县第一中学」(135) → 并入。
# 故两项由 DROP_DUP 改判 MOVE（dst 用已存在的市组标签，prepare 会 ADOPT）。
PLAN = [
    dict(src='榆中县朝阳学校', src_wid='etZUH8SgAAcmXnWcKgaa-Je1QAI1n43Q', src_grp='学校-榆中县',
         dst_grp='学校-兰州市', dst_name='榆中县朝阳学校', action='MOVE'),
    dict(src='榆中县文成小学', src_wid='etZUH8SgAAeff5QqcW374bFYept3Eyug', src_grp='学校-榆中县',
         dst_grp='学校-兰州市', dst_name='榆中县文成小学', action='MOVE'),
    dict(src='榆中县博雅小学', src_wid='etZUH8SgAAFxqp_w-mQYg3NMMhUka_hg', src_grp='学校-榆中县',
         dst_grp='学校-兰州市', dst_name='榆中县博雅小学', action='MOVE'),
    dict(src='榆中县栖云小学', src_wid='etZUH8SgAAh5DYgeMKOlg5FTpIpV6l4w', src_grp='学校-榆中县',
         dst_grp='学校-兰州市', dst_name='榆中县栖云小学', action='MOVE'),
    dict(src='榆中县崇文实验学校', src_wid='etZUH8SgAArNx-XVYtMvo4muSETQ2n4w', src_grp='学校-榆中县',
         dst_grp='学校-兰州市', dst_name='榆中县崇文实验学校', action='MOVE'),
    dict(src='民勤一中', src_wid='etZUH8SgAAFZZqNaVdykME0vt8HTMSpQ', src_grp='学校-民勤县',
         dst_grp='学校-武威市', dst_name='民勤县第一中学', action='MOVE'),
]
BY_SRC = {p['src_wid']: p for p in PLAN}
SRC_WIDS = set(BY_SRC)
MOVE_WIDS = {p['src_wid'] for p in PLAN if p['action'] == 'MOVE'}

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


def sqlx(q):
    """同 sql()，但 SQL 出错时抛异常 —— 写操作必须显式失败，不能静默吞（曾因此误报 ok）。"""
    r = subprocess.run(['mysql', '-h', env['DB_HOST'], '-u', env['DB_USERNAME'],
                        '-p' + env['DB_PASSWORD'], '-N', '-B', '-e', q, 'bookstore_qrcode'],
                       capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError('SQL 失败(rc=%s): %s' % (r.returncode, r.stderr.strip()[:300]))
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


def groups():
    """group_name -> group_id"""
    d = api('externalcontact/get_corp_tag_list?')
    return {g.get('group_name'): g.get('group_id') for g in d.get('tag_group', [])}


def wid_index():
    """wid -> (group_name, tag_name)"""
    d = api('externalcontact/get_corp_tag_list?')
    idx = {}
    for g in d.get('tag_group', []):
        for t in g.get('tag', []):
            idx[t['id']] = (g.get('group_name', ''), t.get('name', ''))
    return idx


def iter_all():
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


def read_target_map():
    m = {}
    if os.path.exists(TARGET_MAP):
        for l in open(TARGET_MAP, encoding='utf-8'):
            p = l.rstrip('\n').split('\t')
            if len(p) >= 2:
                m[p[0]] = p[1]
    return m


# ---------------------------------------------------------------------------
def cmd_check():
    log('=== check %s ===' % time.strftime('%F %T'))
    idx = wid_index()
    gs = groups()
    if not idx:
        log('!! 取不到企微标签列表，中止')
        return
    # 目标组是否存在
    for g in ('学校-兰州市', '学校-武威市'):
        log('  目标组[%s] 存在=%s group_id=%s' % (g, g in gs, gs.get(g)))
    ok = True
    for p in PLAN:
        log('  ---- %s  (%s) ----' % (p['src'], p['action']))
        cur = idx.get(p['src_wid'])
        log('     src  wid=%s  现=%s' % (p['src_wid'], cur))
        if not cur or cur[0] != p['src_grp'] or cur[1] != p['src']:
            log('     !! src wid 组/名不符，停'); ok = False
        same = [w for w, (g, n) in idx.items() if g == p['dst_grp'] and n == p['dst_name']]
        log('     目标组[%s] 里名=\'%s\' 的现有标签 wid=%s' % (p['dst_grp'], p['dst_name'], same))
        if p['action'] == 'MOVE':
            if same:
                log('     !! 目标组已存在同名 —— 应改为 DROP_DUP，停'); ok = False
            if p['dst_grp'] not in gs:
                log('     !! 目标组不存在，无法新建，停'); ok = False
        else:  # DROP_DUP
            if not same:
                log('     !! 目标组没有同名替代，删了会没去处，停'); ok = False
    log('  ---- 本地绑定计数（customer_tag） ----')
    wids = "','".join(sorted(SRC_WIDS))
    rows = sql("SELECT t.wecom_tag_id, t.group_keyword, t.name, COUNT(ct.id) "
               "FROM tag t LEFT JOIN customer_tag ct ON ct.tag_id=t.id "
               "WHERE t.wecom_tag_id IN ('%s') GROUP BY t.wecom_tag_id, t.group_keyword, t.name" % wids)
    tot = 0
    for r in rows:
        c = r.split('\t')
        tot += int(c[3])
        log('     wid=%s [gk=%s] %s  客户 %s' % (c[0], c[1], c[2], c[3]))
    log('  本地绑定合计 %d（含重复行，需按 wid 去重看真实人数）' % tot)
    log('  结果：%s' % ('全部通过 ✔（可继续 probe-owner/p Prepare）' if ok else '有断言失败 ✘ —— 先修再跑'))
    log('=== check 结束 ===')


def cmd_probe_owner():
    """归属探测：把源标签 edit 成"同名"（幂等 no-op）。非本应用创建会返回 81011。"""
    if not GO:
        log('probe-owner 未加 --go（会对每个 src 标签发一次 edit_corp_tag 同名改名）。')
    log('=== probe-owner %s ===' % time.strftime('%F %T'))
    for p in PLAN:
        r = api('externalcontact/edit_corp_tag',
                {'id': p['src_wid'], 'name': p['src']}) if GO else {'errcode': 'SKIP', 'errmsg': 'dry'}
        ec = r.get('errcode')
        verdict = '可删/可改（本应用创建）' if ec in (0, None) else ('81011 非本应用 ✘' if ec == 81011 else str(ec))
        log('  %-10s wid=%s -> %s %s' % (p['src'], p['src_wid'], verdict, '' if GO else '(dry)'))


def cmd_prepare():
    """在目标组为 MOVE 项落 src_wid -> dst_wid 映射：已存在同名则采用，否则新建。

    add_corp_tag 响应形状：{'errcode':0,'tag_group':{'group_id':..,'tag':[{'id':..}]}}
    —— tag_group 是 **dict 不是 list**（早前 [0] 取值即 KeyError: 0 崩溃点）。
    """
    gid = groups()
    idx = wid_index()  # wid -> (group_name, tag_name)
    existing = {}      # (grp, name) -> wid
    for w, (g, n) in idx.items():
        existing[(g, n)] = w
    if not GO:
        log('prepare 未加 --go。将确保以下目标标签存在：')
        for p in PLAN:
            if p['action'] == 'MOVE':
                have = existing.get((p['dst_grp'], p['dst_name']))
                log('   [%s] \'%s\'  %s' % (p['dst_grp'], p['dst_name'],
                                            ('已存在 wid=%s（采用）' % have) if have else '需新建'))
        log('（加 --go 才真建；已存在的映射会跳过）')
        return
    done = read_target_map()
    log('=== prepare GO %s ===' % time.strftime('%F %T'))
    for p in PLAN:
        if p['action'] != 'MOVE':
            continue
        if p['src_wid'] in done:
            log('  SKIP %s（已有映射 dst=%s）' % (p['src'], done[p['src_wid']]))
            continue
        have = existing.get((p['dst_grp'], p['dst_name']))
        if have:
            log('  ADOPT %-10s [%s]\'%s\' 已存在 dst_wid=%s'
                % (p['src'], p['dst_grp'], p['dst_name'], have))
            new = have
        elif p['dst_grp'] not in gid:
            log('  !! %s 组不存在，跳过' % p['dst_grp']); continue
        else:
            r = api('externalcontact/add_corp_tag',
                    {'group_id': gid[p['dst_grp']], 'tag': [{'name': p['dst_name']}]})
            new = None
            if r.get('errcode') in (0, None):
                tg = r.get('tag_group') or {}
                new = (tg.get('tag') or [{}])[0].get('id')
            log('  ADD   %-10s [%s]\'%s\' -> errcode=%s dst_wid=%s %s'
                % (p['src'], p['dst_grp'], p['dst_name'], r.get('errcode'), new, r.get('errmsg', '')))
        if new:
            with open(TARGET_MAP, 'a', encoding='utf-8') as f:
                f.write('%s\t%s\t%s\t%s\n' % (p['src_wid'], new, p['dst_grp'], p['dst_name']))
        time.sleep(0.3)


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
    log('=== census 汇总（按源标签，仅 MOVE 项应 >0）===')
    for p in PLAN:
        log('  %-14s %s   %d 关注关系' % (p['src'], p['action'], cnt.get(p['src_wid'], 0)))
    log('=== census 结束 %s ===' % time.strftime('%F %T'))


def cmd_retag():
    tmap = read_target_map()
    if not GO:
        log('retag 未加 --go，dry-run 前 20 条：')
        n = 0
        for l in open(CENSUS, encoding='utf-8') if os.path.exists(CENSUS) else []:
            p = l.rstrip('\n').split('\t')
            if len(p) >= 3 and p[1] in MOVE_WIDS:
                log('  [dry] %s  %s→dst(%s)  uid=%s' % (p[0][-8:], BY_SRC[p[1]]['src'],
                                                       tmap.get(p[1]), p[2]))
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
        if len(p) >= 3 and p[1] in MOVE_WIDS and (p[0] + '|' + p[1]) not in done:
            rows.append((p[0], p[1], p[2]))
    log('=== retag GO %s 待打 %d（已 OK %d）===' % (time.strftime('%F %T'), len(rows), len(done)))
    n = 0
    for eid, src_wid, uid in rows:
        dst = tmap.get(src_wid)
        if not dst:
            log('  !! %s 无目标 wid 映射，跳过' % src_wid); continue
        r = api('externalcontact/mark_tag', {'userid': uid, 'external_userid': eid, 'add_tag': [dst]})
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
            c = l.split('\t')
            cnt[c[2]] = cnt.get(c[2], 0) + 1
            if c[2] != 'OK':
                fail[c[1]] = fail.get(c[1], 0) + 1
    log('=== retag 结果 ===')
    for k in sorted(cnt, key=lambda x: -cnt[x]):
        log('  %-10s %d' % (k, cnt[k]))
    for w, c in fail.items():
        log('  !! %s(%s) 失败 %d' % (BY_SRC[w]['src'], w, c))


def cmd_verify():
    """只读全量复枚举：凡带 MOVE 源标签的客户，必须同时带对应目标标签，否则记 MISSING。"""
    tmap = read_target_map()
    dst_wids = set(tmap.values())
    relevant = MOVE_WIDS | dst_wids
    log('=== verify %s（全量复枚举）===' % time.strftime('%F %T'))
    seen = {}
    for eid, uid, tags in iter_all():
        rel = [w for w in tags if w in relevant]
        if rel:
            seen.setdefault(eid, set()).update(rel)
    stat = {}
    with open(VERIFY, 'w', encoding='utf-8') as out:
        for eid, ws in seen.items():
            for sw in (ws & MOVE_WIDS):
                dst = tmap.get(sw)
                ok = dst in ws
                out.write('%s\t%s\t%s\n' % (eid, sw, 'OK' if ok else 'MISSING'))
                d = stat.setdefault(sw, {'OK': 0, 'MISSING': 0})
                d['OK' if ok else 'MISSING'] += 1
    log('=== verify 汇总 ===')
    total_missing = 0
    for p in PLAN:
        if p['action'] != 'MOVE':
            continue
        d = stat.get(p['src_wid'], {'OK': 0, 'MISSING': 0})
        total_missing += d['MISSING']
        log('  %-14s 已带目标 %d / 缺目标 %d' % (p['src'], d['OK'], d['MISSING']))
    log('  MISSING 合计 %d  %s' % (total_missing,
        '（可 delete）' if total_missing == 0 else '（不可删！先重跑 retag 再 verify）'))


def cmd_delete():
    """删源标签。MOVE 项受 verify 门禁；DROP_DUP 项 0 客户，直接删。"""
    if not GO:
        log('delete 未加 --go。将删：')
        for p in PLAN:
            log('  %-14s %-6s %s' % (p['src'], p['action'], p['src_wid']))
        log('（加 --go 才真删；MOVE 项需 verify MISSING=0）')
        return
    miss, fail = {}, {}
    if os.path.exists(VERIFY):
        for l in open(VERIFY, encoding='utf-8'):
            if l.strip() and l.split('\t')[2].strip() != 'OK':
                miss[l.split('\t')[1]] = miss.get(l.split('\t')[1], 0) + 1
    if os.path.exists(RETAG):
        for l in open(RETAG, encoding='utf-8'):
            if l.strip() and l.split('\t')[2].strip() != 'OK':
                fail[l.split('\t')[1]] = fail.get(l.split('\t')[1], 0) + 1
    log('=== delete GO %s ===' % time.strftime('%F %T'))
    for p in PLAN:
        if p['action'] == 'MOVE':
            if miss.get(p['src_wid'], 0) > 0:
                log('  SKIP %s —— verify 有 %d 个"带源不带目标"，删了会丢校标签' % (p['src'], miss[p['src_wid']])); continue
            if fail.get(p['src_wid'], 0) > 0:
                log('  SKIP %s —— 有 %d 条加标失败，先重跑 retag' % (p['src'], fail[p['src_wid']])); continue
        r = api('externalcontact/del_corp_tag', {'tag_id': [p['src_wid']]})
        log('  DEL  %-14s %s -> errcode=%s %s' % (p['src'], p['src_wid'], r.get('errcode'), r.get('errmsg', '')))
        time.sleep(0.3)
    idx = wid_index()
    log('=== 复查（源应消失）===')
    for p in PLAN:
        log('  %-14s 仍在？%s' % (p['src'], p['src_wid'] in idx))


def cmd_local():
    """本地镜像改造（企微侧完成后跑）。幂等，可重跑。

    每项 MOVE：
      1) 按 (dst_name, dst_grp) 找本地目标行 —— 唯一键 uk_tag_name_group 保证至多一行，
         且该行可能正是那条「组名已是目标组、wid 还是旧 wid」的错标行（如 文成 #1914）。
         找到则把它的 wecom_tag_id 改成新 dst_wid；找不到则新建。
         ★ 不能盲目 INSERT：会撞 uk_tag_name_group（曾因此整批 SQL 静默失败）。
      2) 删「客户已同时拥有目标行」的旧行绑定（避免撞 uk_customer_tag）。
      3) 把旧 wid 行上的其余绑定 UPDATE 到目标行；再删旧 wid 行。

    一律按 wecom_tag_id 匹配、不按 group_keyword —— 绑定常挂在错组行上（如 朝阳 #1846
    gk=学校-兰州市 带 835），按组名删会连绑定一起丢。
    """
    tmap = read_target_map()
    movers = [p for p in PLAN if p['action'] == 'MOVE']
    drops = [p for p in PLAN if p['action'] == 'DROP_DUP']
    log('=== local %s (%s) ===' % (time.strftime('%F %T'), 'GO' if GO else 'DRY'))
    ok = True
    for p in movers:
        d = tmap.get(p['src_wid'])
        if not d:
            log('  !! %s 无 dst 映射（先跑 prepare），跳过' % p['src']); ok = False; continue
        s = p['src_wid']
        n_src = sql("SELECT COUNT(*) FROM tag WHERE wecom_tag_id='%s'" % s)[0]
        n_bind = sql("SELECT COUNT(*) FROM customer_tag ct JOIN tag t ON t.id=ct.tag_id "
                     "WHERE t.wecom_tag_id='%s'" % s)[0]
        tgt = sql("SELECT id, wecom_tag_id FROM tag WHERE name='%s' AND group_keyword='%s'"
                  % (p['dst_name'], p['dst_grp']))
        log('  %-10s src行=%s(绑定%s)  目标行(name,gk)=%s' % (p['src'], n_src, n_bind, tgt or '(将新建)'))
        if not GO:
            continue
        try:
            if tgt:
                did = tgt[0].split('\t')[0]
                sqlx("UPDATE tag SET wecom_tag_id='%s' WHERE id=%s" % (d, did))
            else:
                sqlx("INSERT INTO tag (name,type,parent_id,wecom_tag_id,group_keyword) "
                     "VALUES ('%s','system',NULL,'%s','%s')" % (p['dst_name'], d, p['dst_grp']))
                did = sqlx("SELECT id FROM tag WHERE name='%s' AND group_keyword='%s'"
                           % (p['dst_name'], p['dst_grp']))[0]
            # ★ 目标行 wid 已改，故下面按 wid='s' 选出的旧行不含目标行
            sqlx("DELETE ct FROM customer_tag ct JOIN tag o ON o.id=ct.tag_id AND o.wecom_tag_id='%s' "
                 "JOIN customer_tag c2 ON c2.customer_id=ct.customer_id AND c2.tag_id=%s" % (s, did))
            sqlx("UPDATE customer_tag ct JOIN tag o ON o.id=ct.tag_id AND o.wecom_tag_id='%s' "
                 "SET ct.tag_id=%s" % (s, did))
            sqlx("DELETE FROM tag WHERE wecom_tag_id='%s'" % s)
            log('     APPLY ok (本地目标行 id=%s)' % did)
        except Exception as e:
            log('     !! 失败: %s' % e); ok = False
    for p in drops:
        n_bind = sql("SELECT COUNT(*) FROM customer_tag ct JOIN tag t ON t.id=ct.tag_id "
                     "WHERE t.wecom_tag_id='%s'" % p['src_wid'])[0]
        log('  %-10s(DROP_DUP) 绑定=%s' % (p['src'], n_bind))
        if n_bind != '0':
            log('    !! 竟有绑定，跳过删除（需人工核对）'); ok = False; continue
        if not GO:
            continue
        try:
            sqlx("DELETE FROM tag WHERE wecom_tag_id='%s'" % p['src_wid'])
            log('     DEL ok')
        except Exception as e:
            log('     !! 失败: %s' % e); ok = False
    if GO:
        log('  === 复查 ===')
        for p in movers:
            d = tmap.get(p['src_wid'])
            left = sql("SELECT COUNT(*) FROM tag WHERE wecom_tag_id='%s'" % p['src_wid'])[0]
            bind = sql("SELECT COUNT(*) FROM customer_tag ct JOIN tag t ON t.id=ct.tag_id "
                       "WHERE t.wecom_tag_id='%s'" % d)[0]
            log('    %-10s 旧行剩=%s 新行绑定=%s' % (p['src'], left, bind))
    log('=== local 结束 %s ===' % ('全部通过' if ok else '有失败项，检查上面 !!'))


if __name__ == '__main__':
    {'check': cmd_check, 'probe-owner': cmd_probe_owner, 'prepare': cmd_prepare,
     'census': cmd_census, 'retag': cmd_retag, 'verify': cmd_verify,
     'delete': cmd_delete, 'local': cmd_local}.get(CMD, cmd_check)()
