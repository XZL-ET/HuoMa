# -*- coding: utf-8 -*-
"""只读：比对企微活标签 vs 本地 tag 行，找出「企微有、火马没有」的标签；
   并列出回灌缺口里「客户不在火马」的那几条。"""
import json
import subprocess
import time
import urllib.request
from collections import Counter

env = {}
for line in open('/etc/systemd/system/huoma.env'):
    s = line.strip()
    if s and not s.startswith('#') and '=' in s:
        k, v = s.split('=', 1)
        env[k.strip()] = v.strip()

DB = 'bookstore_qrcode'


def sql(q):
    return subprocess.run(['mysql', '-h', env['DB_HOST'], '-u', env['DB_USERNAME'],
                           '-p' + env['DB_PASSWORD'], '-N', '-B', '-e', q, DB],
                          capture_output=True, text=True).stdout.strip().splitlines()


_tok = {'v': None, 'at': 0}


def token():
    if not _tok['v'] or time.time() - _tok['at'] > 6000:
        d = json.loads(urllib.request.urlopen(
            'https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=' + env['WECOM_CORP_ID']
            + '&corpsecret=' + env['WECOM_CORP_SECRET'], timeout=20).read())
        _tok['v'] = d['access_token']
        _tok['at'] = time.time()
    return _tok['v']


d = json.loads(urllib.request.urlopen(
    'https://qyapi.weixin.qq.com/cgi-bin/externalcontact/get_corp_tag_list?access_token='
    + token(), timeout=30).read())

wecom = {}
for g in d.get('tag_group', []):
    for t in g.get('tag', []):
        wecom[t['id']] = (g.get('group_name', ''), t.get('name', ''))

# 本地
rows = sql("SELECT wecom_tag_id, group_keyword, name FROM tag "
           "WHERE wecom_tag_id IS NOT NULL AND wecom_tag_id<>''")
local_ids = set()
for r in rows:
    local_ids.add(r.split('\t')[0])

# 本地「死 wid」（火马有、企微无）
wecom_ids = set(wecom)
dead = local_ids - wecom_ids
missing = wecom_ids - local_ids

print('企微活标签 = %d' % len(wecom))
print('本地有 wid 的行 distinct = %d' % len(local_ids))
print('本地死 wid（企微已无）= %d' % len(dead))
print('企微有·本地无 = %d' % len(missing))
print('--- 企微有·本地无 按组 ---')
for gname, c in Counter(wecom[w][0] for w in missing).most_common():
    print('  %-16s %d' % (gname, c))
print('--- 企微有·本地无 明细（最多 60）---')
for w in sorted(missing, key=lambda x: (wecom[x][0], wecom[x][1]))[:60]:
    print('  [%s] %s' % wecom[w])

# 回灌缺口里「客户不在火马」的 eid
miss_pairs = []
for line in open('/tmp/backfill_missing.tsv', encoding='utf-8'):
    line = line.rstrip('\n')
    if line:
        miss_pairs.append(line.split('\t', 1))
print('--- 缺口里客户不在火马的 eid（去重）---')
eids = sorted({e for _, e in miss_pairs})
# 批量查
found = set()
CH = 500
for i in range(0, len(eids), CH):
    chunk = eids[i:i + CH]
    inlist = ','.join("'" + x.replace("'", "''") + "'" for x in chunk)
    for x in sql("SELECT external_userid FROM customer WHERE external_userid IN (%s)" % inlist):
        found.add(x)
no_cust = [e for e in eids if e not in found]
print('缺口 eid 去重 = %d ; 其中不在 customer = %d' % (len(eids), len(no_cust)))
for e in no_cust[:20]:
    wids = sorted({w for w, ee in miss_pairs if ee == e})
    nms = ['[%s]%s' % wecom.get(w, ('?', '?')) for w in wids]
    print('  eid=%s  绑定标签: %s' % (e, ', '.join(nms)))
