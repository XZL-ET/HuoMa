# -*- coding: utf-8 -*-
"""标签对账：本地 customer_tag  vs  企微实际绑定（只读）

比较单位 = (客户 external_userid, 标签 wecom_tag_id) 对。
  企微侧：遍历全部成员 → batch/get_by_user 拿每个 (成员,客户) 的 tag_id 列表 → 落 (wid, eid) 去重
  本地侧：customer_tag ⋈ tag ⋈ customer → (wid, eid)
差异：
  企微独有 = 企微有该绑定、火马没有   → 火马"漏记"（员工手动打标 / 别系统打标 / 双系统）
  本地独有 = 火马有、企微没有         → 火马"多记"（死wid僵尸绑定 / 企微已删该绑定）

子命令：
  census   遍历企微全量成员，写 /tmp/recon_wecom.tsv (wid\teid)。慢，约 30min
  local    从 DB 导 /tmp/recon_local.tsv (wid\teid)
  diff     两侧 sort -u 后流式比对，出总体 + 逐 wid 差异（top 漏记/多记）
  all      依次 census → local → diff

产物：/tmp/recon_{wecom,local}.tsv  /tmp/recon_diff_by_wid.tsv  /tmp/recon.log
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from collections import Counter

WECOM_TSV = '/tmp/recon_wecom.tsv'
LOCAL_TSV = '/tmp/recon_local.tsv'
BYWID_TSV = '/tmp/recon_diff_by_wid.tsv'
LOG = '/tmp/recon.log'

CMD = next((a for a in sys.argv[1:] if not a.startswith('-')), 'diff')

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


def cmd_census():
    log('=== census %s ===' % time.strftime('%F %T'))
    n = 0
    tags = 0
    with open(WECOM_TSV, 'w', encoding='utf-8') as f:
        for eid, _uid, tagids in iter_all():
            for wid in tagids:
                f.write('%s\t%s\n' % (wid, eid))
                tags += 1
            n += 1
            if n % 20000 == 0:
                log('  关系 %d 行, 标签对 %d' % (n, tags))
    log('census 完成: 关系(成员,客户)=%d  原始标签对=%d  文件=%s'
        % (n, tags, WECOM_TSV))


def cmd_local():
    log('=== local %s ===' % time.strftime('%F %T'))
    rows = sql("SELECT t.wecom_tag_id, c.external_userid FROM customer_tag ct "
               "JOIN tag t ON ct.tag_id=t.id JOIN customer c ON ct.customer_id=c.id "
               "WHERE t.wecom_tag_id IS NOT NULL AND t.wecom_tag_id<>''")
    with open(LOCAL_TSV, 'w', encoding='utf-8') as f:
        f.write('\n'.join(rows) + '\n')
    log('local 导出 %d 行 -> %s' % (len(rows), LOCAL_TSV))


def _sorted_unique(p):
    out = p + '.s'
    subprocess.run(['sort', '-u', p, '-o', out], check=True)
    return out


def _iter(p):
    with open(p, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if line:
                yield line


def cmd_diff():
    log('=== diff %s ===' % time.strftime('%F %T'))
    idx = wid_index()
    ws, ls = _sorted_unique(WECOM_TSV), _sorted_unique(LOCAL_TSV)
    # 用 set 求差（两指针流式合并曾出过 bug；set 内存 ~1GB 可控）
    W = set(_iter(ws))
    L = set(_iter(ls))
    nw, nl = len(W), len(L)
    only_w, only_l = W - L, L - W
    both = len(W & L)
    wo, lo = len(only_w), len(only_l)
    c_wo = Counter(x.split('\t')[0] for x in only_w)
    c_lo = Counter(x.split('\t')[0] for x in only_l)

    log('--- 总体（单位：客户×标签 去重对）---')
    log('  企微 = %d   火马 = %d' % (nw, nl))
    log('  一致 = %d' % both)
    log('  企微独有(火马漏记) = %d' % wo)
    log('  火马独有(火马多记) = %d' % lo)
    if nw:
        log('  火马对企微的覆盖率(按对) = %.2f%%' % (100.0 * both / nw))

    def name(wid):
        g, n = idx.get(wid, ('?', '?'))
        return '[%s] %s' % (g, n)

    log('--- 企微独有 top 30（火马漏得最多）---')
    for wid, c in sorted(c_wo.items(), key=lambda x: -x[1])[:30]:
        log('  %-6d %s' % (c, name(wid)))
    log('--- 火马独有 top 30（火马多记）---')
    for wid, c in sorted(c_lo.items(), key=lambda x: -x[1])[:30]:
        log('  %-6d %s' % (c, name(wid)))

    allw = set(c_wo) | set(c_lo)
    with open(BYWID_TSV, 'w', encoding='utf-8') as f:
        f.write('wid\t组\t名\t企微独有\t火马独有\n')
        for wid in sorted(allw, key=lambda x: -(c_wo.get(x, 0) + c_lo.get(x, 0))):
            g, n = idx.get(wid, ('?', '?'))
            f.write('%s\t%s\t%s\t%d\t%d\n' % (wid, g, n, c_wo.get(wid, 0), c_lo.get(wid, 0)))
    log('逐 wid 明细 -> %s （%d 个 wid 有差异）' % (BYWID_TSV, len(allw)))


SUBS = {'census': cmd_census, 'local': cmd_local, 'diff': cmd_diff}


def main():
    if CMD == 'all':
        cmd_census()
        cmd_local()
        cmd_diff()
    else:
        SUBS.get(CMD, cmd_diff)()


if __name__ == '__main__':
    main()
