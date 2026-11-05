# -*- coding: utf-8 -*-
"""S0 —— 本地 tag 镜像整理（只动本地库，零企微写、零客户风险）

背景见 docs/2026-10-15-tag-standardization-audit.md §2.3 / §3-S0。
本地 `tag` 表不是企微可信视图：3474 行里有 830 行重复、23 行指向企微已删标签。
目标（S0）：
  1. 删死 wid 行（企微已无此标签）
  2. 消重复行：同一 wid 只留 1 行 canonical（group_keyword = 企微真实组）
  3. 名不一致 16 行：8 行学期升学按现状保留；4 行（学校名缺前缀 / 临夏市→临夏州）可修

⚠ §4-2 的教训：**绑定常挂在"错组"那一行上**，不是挂在组名与企微一致的行上。
   所以"消重复"不能直接删多余行 —— 必须先把这些行上的 customer_tag 重接到 canonical 行，再删。

子命令：
  analyze   只读。分类死wid/重复wid/名不一致，量化每类要重接/删除的绑定数，输出 /tmp/s0_analyze.tsv
  apply     (待实现) 按 analyze 结论执行：relink 绑定 → 删多余行 / 死行
  verify    只读。复查：残留重复=0、残留死wid=0、绑定总数守恒

顺序：analyze → (design) → apply → verify
"""
import os
import subprocess
import sys
import time
import json
import urllib.request

TSV = '/tmp/s0_analyze.tsv'
GO = '--go' in sys.argv
CMD = next((a for a in sys.argv[1:] if not a.startswith('-')), 'analyze')

env = {}
for line in open('/etc/systemd/system/huoma.env'):
    s = line.strip()
    if s and not s.startswith('#') and '=' in s:
        k, v = s.split('=', 1)
        env[k.strip()] = v.strip()


def log(msg):
    print(msg, flush=True)


def sql(q):
    r = subprocess.run(['mysql', '-h', env['DB_HOST'], '-u', env['DB_USERNAME'],
                        '-p' + env['DB_PASSWORD'], '-N', '-B', '-e', q, 'bookstore_qrcode'],
                       capture_output=True, text=True)
    return r.stdout.strip().splitlines()


def sqlx(q):
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
    """wid -> (group_name, tag_name)，来自 get_corp_tag_list（只读）"""
    d = api('externalcontact/get_corp_tag_list?')
    idx = {}
    for g in d.get('tag_group', []):
        for t in g.get('tag', []):
            idx[t['id']] = (g.get('group_name', ''), t.get('name', ''))
    return idx


def fk_info():
    out = []
    for l in sql("SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                 "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='bookstore_qrcode' "
                 "AND REFERENCED_TABLE_NAME IS NOT NULL"):
        out.append(l)
    rc = {}
    for l in sql("SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS "
                 "WHERE CONSTRAINT_SCHEMA='bookstore_qrcode'"):
        p = l.split('\t')
        rc[p[0]] = p[1]
    return out, rc


# ---------------------------------------------------------------------------
def cmd_analyze():
    log('=== S0 analyze %s ===' % time.strftime('%F %T'))
    rows = sql("SELECT id, name, IFNULL(type,''), IFNULL(group_keyword,''), IFNULL(wecom_tag_id,'') FROM tag")
    tags = {}
    for l in rows:
        p = l.split('\t')
        tags[int(p[0])] = dict(name=p[1], type=p[2], gk=p[3], wid=p[4])

    cnt = {}
    for l in sql("SELECT tag_id, COUNT(*) FROM customer_tag GROUP BY tag_id"):
        p = l.split('\t')
        cnt[int(p[0])] = int(p[1])
    total_bindings = sum(cnt.values())

    idx = wid_index()
    log('本地 tag 行=%d  distinct wid=%d  企微活标签=%d'
        % (len(tags), len(set(t['wid'] for t in tags.values())), len(idx)))

    fks, rc = fk_info()
    log('--- 外键 / ON DELETE ---')
    for l in fks:
        p = l.split('\t')
        log('  %s.%s -> %s.%s' % (p[0], p[1], p[2], p[3]))
    for k, v in rc.items():
        log('  %s ON DELETE %s' % (k, v))

    bywid = {}
    for tid, t in tags.items():
        bywid.setdefault(t['wid'], []).append(tid)

    out = ['\t'.join(['kind', 'wid', 'grp_wecom', 'name_wecom', 'row_id', 'gk_local', 'name_local', 'bindings', 'note'])]

    # ---- 1. 死 wid：wid 非空且不在企微 index ----
    dead_rows = []
    for wid, tids in bywid.items():
        if wid and wid not in idx:
            for tid in tids:
                dead_rows.append((wid, tid, cnt.get(tid, 0)))
    dead_bind = sum(b for _, _, b in dead_rows)
    log('--- 死 wid 行 %d 行 / %d wid，涉及绑定 %d 条 ---'
        % (len(dead_rows), len(set(w for w, _, _ in dead_rows)), dead_bind))
    for wid, tid, b in sorted(dead_rows, key=lambda x: -x[2]):
        t = tags[tid]
        log('  #%-5d [%-10s] %-22s bindings=%d' % (tid, t['gk'], t['name'], b))
        out.append('\t'.join(['DEAD', wid, '', '', str(tid), t['gk'], t['name'], str(b), '']))

    # ---- 空 wid 行 ----
    emptywid = [tid for tid, t in tags.items() if not t['wid']]
    log('--- 空 wecom_tag_id 行 %d 行（另计）---' % len(emptywid))
    for tid in emptywid:
        t = tags[tid]
        log('  #%-5d [%-10s] %-22s bindings=%d' % (tid, t['gk'], t['name'], cnt.get(tid, 0)))

    # ---- 2. 重复 wid ----
    dup_wids = {w: t for w, t in bywid.items() if w and len(t) > 1}
    log('--- 重复 wid %d 个 / 多出 %d 行 ---'
        % (len(dup_wids), sum(len(v) - 1 for v in dup_wids.values())))
    relink_bind = 0        # 需重接到 canonical 的绑定
    del_bind_direct = 0    # 挂在待删行、且无客户 → 直接删
    no_canon = 0           # 无一行 gk 与企微一致
    multi_canon = 0        # 多行 gk 都与企微一致
    for wid, tids in sorted(dup_wids.items(), key=lambda x: -len(x[1])):
        grp, nm = idx.get(wid, ('', ''))
        canon = [tid for tid in tids if tags[tid]['gk'] == grp]
        if not canon:
            no_canon += 1
        if len(canon) > 1:
            multi_canon += 1
        keep = min(canon) if canon else min(tids)
        extra = [tid for tid in tids if tid != keep]
        rb = sum(cnt.get(tid, 0) for tid in extra)
        relink_bind += rb
        if rb:
            log('  wid=%s 企微组=[%s] 行数=%d canonical=#%d 待删行绑定=%d'
                % (wid, grp, len(tids), keep, rb))
            for tid in tids:
                mark = 'KEEP' if tid == keep else ('NOCANON' if not canon else 'DEL')
                out.append('\t'.join(['DUP', wid, grp, nm, str(tid), tags[tid]['gk'],
                                      tags[tid]['name'], str(cnt.get(tid, 0)), mark]))
    log('重复类：需重接绑定 %d 条；无 canonical 的 wid %d 个；多 canonical 的 wid %d 个'
        % (relink_bind, no_canon, multi_canon))

    # ---- 3. 名不一致 ----
    mm = []
    for tid, t in tags.items():
        if t['wid'] and t['wid'] in idx:
            g, nm = idx[t['wid']]
            if nm and t['name'] != nm:
                mm.append((tid, t['name'], nm))
    log('--- 名不一致 %d 行 ---' % len(mm))
    for tid, ln, rn in sorted(mm):
        log('  #%-5d 本地 %-14s vs 企微 %-14s' % (tid, ln, rn))
        out.append('\t'.join(['NAME', tags[tid]['wid'], '', rn, str(tid), tags[tid]['gk'], ln, str(cnt.get(tid, 0)), '']))

    with open(TSV, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')

    log('--- 汇总 ---')
    log('绑定总数(customer_tag 行)=%d' % total_bindings)
    log('死wid行=%d(绑定%d)  重复多出行=%d(需重接%d)  名不一致=%d  空wid行=%d'
        % (len(dead_rows), dead_bind, sum(len(v) - 1 for v in dup_wids.values()), relink_bind, len(mm), len(emptywid)))
    log('明细已写 %s' % TSV)


def cmd_diag():
    from collections import Counter
    rows = sql("SELECT id, name, IFNULL(group_keyword,''), IFNULL(wecom_tag_id,'') FROM tag")
    tags = {}
    for l in rows:
        p = l.split('\t')
        tags[int(p[0])] = dict(name=p[1], gk=p[2], wid=p[3] if len(p) > 3 else '')
    idx = wid_index()
    bywid = {}
    for tid, t in tags.items():
        bywid.setdefault(t['wid'], []).append(tid)

    # 1. 单行错组：wid 存活、只有 1 行、且 gk != 企微组
    bad = []
    for wid, tids in bywid.items():
        if wid and wid in idx and len(tids) == 1:
            t = tags[tids[0]]
            if t['gk'] != idx[wid][0]:
                bad.append((tids[0], t['name'], t['gk'], idx[wid][0], idx[wid][1]))
    log('--- 单行错组 %d 行（需把 gk 改回企微组；非"重复"类）---' % len(bad))
    for g, n in Counter(x[3] for x in bad).most_common():
        log('   本地gk -> 应改到 [%s]  : %d' % (g, n))
    for x in bad[:20]:
        log('   #%-5d 本地[%-10s]%-14s  ->  应[%s] %s' % x)

    # 2. 多 canonical 的 wid
    log('--- 多 canonical 的 wid ---')
    for wid, tids in bywid.items():
        if wid and wid in idx and len(tids) > 1:
            grp = idx[wid][0]
            canon = [t for t in tids if tags[t]['gk'] == grp]
            if len(canon) > 1:
                log('   wid=%s 企微组[%s]' % (wid, grp))
                for t in tids:
                    log('      #%-5d [%-10s] %s' % (t, tags[t]['gk'], tags[t]['name']))

    # 3. 待删 tag 行是否有子行（parent_id 引用）
    kid = {}
    for l in sql("SELECT id, IFNULL(parent_id,'') FROM tag WHERE parent_id IS NOT NULL"):
        p = l.split('\t')
        kid.setdefault(int(p[1]), []).append(int(p[0]))
    log('--- parent_id 有子行的 tag 数 = %d ---' % len(kid))
    for pid, kids in list(kid.items())[:10]:
        log('   #%d 子=[%s]' % (pid, kids))

    # 4. 重复多出行 的 gk 分布（待删行落在哪些组）
    c = Counter()
    for wid, tids in bywid.items():
        if wid and len(tids) > 1:
            grp = idx.get(wid, ('', ''))[0]
            canon = [t for t in tids if tags[t]['gk'] == grp]
            keep = min(canon) if canon else min(tids)
            for t in tids:
                if t != keep:
                    c[tags[t]['gk'] or '(空组)'] += 1
    log('--- 待删的重复行 gk 分布 ---')
    for g, n in c.most_common():
        log('   %-14s %d' % (g, n))

    # 5. canonical 行的 gk 分布（保留行落在哪些组）
    c2 = Counter()
    for wid, tids in bywid.items():
        if wid and len(tids) > 1:
            grp = idx.get(wid, ('', ''))[0]
            canon = [t for t in tids if tags[t]['gk'] == grp]
            keep = min(canon) if canon else min(tids)
            c2[tags[keep]['gk'] or '(空组)'] += 1
    log('--- 保留(canonical)行的 gk 分布 ---')
    for g, n in c2.most_common():
        log('   %-14s %d' % (g, n))

    # 6. 待删行 是否 是 parent；以及 parent 语义样本
    delset = set()
    for wid, tids in bywid.items():
        if wid and wid not in idx:
            delset.update(tids)
        elif wid and len(tids) > 1:
            grp = idx.get(wid, ('', ''))[0]
            canon = [t for t in tids if tags[t]['gk'] == grp]
            keep = min(canon) if canon else min(tids)
            delset.update(t for t in tids if t != keep)
    inter = delset & set(kid)
    log('--- 待删行中"有子行"的 = %d ---' % len(inter))
    for pid in list(inter)[:20]:
        log('   #%d [%s] %s  子=%s' % (pid, tags[pid]['gk'], tags[pid]['name'], kid[pid]))

    log('--- parent 样本（id<=40 且有子行的） ---')
    n = 0
    for pid in sorted(k for k in kid if k <= 40):
        pt = sql("SELECT name,IFNULL(type,''),IFNULL(group_keyword,''),IFNULL(wecom_tag_id,'') FROM tag WHERE id=%d" % pid)
        log('   父#%d %s   子==%s' % (pid, pt[0] if pt else '?', kid[pid][:6]))
        n += 1
        if n >= 8:
            break


def cmd_tree():
    idx = wid_index()
    for pid in (5, 26, 42, 1284):
        p = sql("SELECT name,IFNULL(type,''),IFNULL(group_keyword,''),IFNULL(wecom_tag_id,'') FROM tag WHERE id=%d" % pid)
        if not p:
            continue
        pp = p[0].split('\t')
        live = pp[3] in idx
        log('父#%d  %s  type=%s gk=[%s] widlive=%s' % (pid, pp[0], pp[1], pp[2], live))
        kids = sql("SELECT id,name,IFNULL(type,''),IFNULL(group_keyword,''),IFNULL(wecom_tag_id,'') "
                   "FROM tag WHERE parent_id=%d LIMIT 40" % pid)
        from collections import Counter
        gkc = Counter()
        deadk = 0
        for k in kids:
            kp = k.split('\t')
            gkc[kp[3] or '(空)'] += 1
            if kp[4] and kp[4] not in idx:
                deadk += 1
        log('   子数(样本%d) gk分布=%s  其中死wid子=%d' % (len(kids), dict(gkc), deadk))
        for k in kids[:6]:
            kp = k.split('\t')
            log('      #%s [%s] %s  widlive=%s' % (kp[0], kp[3], kp[1], kp[4] in idx))


def cmd_impact():
    rows = sql("SELECT id, name, IFNULL(type,''), IFNULL(group_keyword,''), IFNULL(wecom_tag_id,'') FROM tag")
    tags = {}
    for l in rows:
        p = l.split('\t')
        tags[int(p[0])] = dict(name=p[1], type=p[2], gk=p[3], wid=p[4] if len(p) > 3 else '')
    idx = wid_index()
    bywid = {}
    for tid, t in tags.items():
        bywid.setdefault(t['wid'], []).append(tid)

    # canonical rule (与 apply 一致): 优先 gk==企微组且 name==企微名, 否则 gk==企微组最小id, 否则最小id
    keep_of = {}
    for wid, tids in bywid.items():
        grp, nm = idx.get(wid, (None, None))
        if grp is None:
            continue  # 死wid 另处理
        if len(tids) == 1:
            continue
        cand = [t for t in tids if tags[t]['gk'] == grp]
        named = [t for t in cand if tags[t]['name'] == nm]
        keep = min(named) if named else (min(cand) if cand else min(tids))
        for t in tids:
            if t != keep:
                keep_of[t] = keep

    deadset = {t for t in tags if tags[t]['wid'] and tags[t]['wid'] not in idx}
    delset = set(keep_of) | deadset

    child_of = {}
    for l in sql("SELECT id, parent_id FROM tag WHERE parent_id IS NOT NULL"):
        p = l.split('\t')
        child_of[int(p[0])] = int(p[1])

    del_parents = [t for t in delset if t in set(child_of.values())]
    log('待删行 = %d ；其中是 parent 的 = %d' % (len(delset), len(del_parents)))
    surv, orphan, remap = 0, 0, 0
    for pid in del_parents:
        kids = [c for c, pa in child_of.items() if pa == pid]
        for c in kids:
            if c in delset:
                remap += 1          # 子行也删 → 无需管 parent_id
            else:
                surv += 1           # 子行保留 → 需要重指向/NULL
                if pid in keep_of:
                    pass            # 可重指向 keep_of[pid]
                else:
                    pass
    # 重算：区分 可重指向 与 只能置 NULL
    remap_to_keep, must_null = 0, 0
    for pid in del_parents:
        for c, pa in child_of.items():
            if pa != pid or c in delset:
                continue
            if pid in keep_of:
                remap_to_keep += 1
            else:
                must_null += 1
    log('  parent被删后：子行也删=%d  子行保留→可重指向(→canonical)=%d  子行保留→须置NULL=%d'
        % (remap, remap_to_keep, must_null))

    # 死wid行中是否有 parent
    dead_parents = [t for t in deadset if t in set(child_of.values())]
    log('死wid行中 是 parent 的 = %d' % len(dead_parents))
    for pid in dead_parents[:20]:
        kids = [c for c, pa in child_of.items() if pa == pid]
        log('   #%d [%s] %s 子=%d' % (pid, tags[pid]['gk'], tags[pid]['name'], len(kids)))


def cmd_count():
    log('=== S0 变更量测算 %s ===' % time.strftime('%F %T'))
    rows = sql("SELECT id, IFNULL(group_keyword,''), IFNULL(wecom_tag_id,'') FROM tag")
    tags = {}
    for l in rows:
        p = l.split('\t')
        tags[int(p[0])] = dict(gk=p[1], wid=p[2] if len(p) > 2 else '')
    idx = wid_index()
    bywid = {}
    for tid, t in tags.items():
        bywid.setdefault(t['wid'], []).append(tid)

    keep_of = {}
    for wid, tids in bywid.items():
        if not wid or wid not in idx or len(tids) == 1:
            continue
        cand = [t for t in tids if tags[t]['gk'] == idx[wid][0]]
        keep = min(cand) if cand else min(tids)
        for t in tids:
            if t != keep:
                keep_of[t] = keep
    deadset = {t for t in tags if tags[t]['wid'] and tags[t]['wid'] not in idx}
    victim = set(keep_of) | deadset

    cnt = {}
    for l in sql("SELECT tag_id, COUNT(*) FROM customer_tag GROUP BY tag_id"):
        p = l.split('\t')
        cnt[int(p[0])] = int(p[1])
    ct_total = sum(cnt.values())

    spare_bind = sum(cnt.get(t, 0) for t in keep_of)          # live 待并/待删绑定
    dead_bind = sum(cnt.get(t, 0) for t in deadset)           # 死wid 待删绑定
    dead_wids = sorted(tags[t]['wid'] for t in deadset)
    inlist = ','.join("'%s'" % w for w in dead_wids)
    R = int(sql("SELECT IFNULL(SUM(c-1),0) FROM (SELECT COUNT(*) c FROM customer_tag ct "
                "JOIN tag t ON ct.tag_id=t.id WHERE t.wecom_tag_id<>'' "
                "AND t.wecom_tag_id NOT IN (%s) GROUP BY ct.customer_id,t.wecom_tag_id "
                "HAVING c>1) x" % inlist)[0]) if dead_wids else 0
    ct_ins = spare_bind - R                                     # 合并实际插入（撞键丢弃 R 条）
    ct_del = spare_bind + dead_bind                             # 删除的绑定行

    log('--- tag 表 ---')
    log('  现有行 = %d' % len(tags))
    log('  删除    = %d  （重复spare %d + 死wid %d）' % (len(victim), len(keep_of), len(deadset)))
    log('  保留行 = %d  （= 企微活标签 %d ?）' % (len(tags) - len(victim), len(idx)))
    log('--- customer_tag 表 ---')
    log('  现有行 = %d' % ct_total)
    log('  删除    = %d  （spare %d + 死wid %d）' % (ct_del, spare_bind, dead_bind))
    log('  插入    = %d  （spare 绑定 %d - 撞键丢弃 %d）' % (ct_ins, spare_bind, R))
    log('  净变化  = %+d' % (ct_ins - ct_del))
    log('  保留行  = %d' % (ct_total + ct_ins - ct_del))
    log('--- 其它 ---')
    log('  parent_id 置NULL = 1 行（#1285）')


SUBS = {'analyze': cmd_analyze, 'diag': cmd_diag, 'tree': cmd_tree, 'impact': cmd_impact, 'count': cmd_count}

if __name__ == '__main__':
    SUBS.get(CMD, cmd_analyze)()
