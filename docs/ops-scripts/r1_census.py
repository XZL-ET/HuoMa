# -*- coding: utf-8 -*-
"""只读普查：R1 的 13,785 位客户，企微侧到底有没有「年级」组标签，并产出补打名单。

R1 = 本地旧行的 wecom_tag_id 落在企微「年级」组（行级判定），Phase A 的 action=LOCAL_ONLY
假设「客户企微侧已对」——但抽样显示约 1/4 的人实际没带。本脚本逐个 externalcontact/get 核验。

分类：
  HAS_TARGET  身上带 target_wecom_tag_id            → 无需动作
  HAS_OTHER   带别的「年级」组标签但非 target        → 人工复核（年级可能已升学）
  NO_GRADE    不带任何「年级」组标签                → 待补打 target_wecom_tag_id
  RELATION_GONE  84061 已非外部联系人               → 打不上
  ERR*        其它错误

输出（可断点续跑，行级即时落盘）：
  /tmp/r1_census_lines.tsv   eid \t cls \t userid \t target_wid \t 现有年级名(逗号分隔)
  /tmp/r1_census.log         运行日志
"""
import json
import os
import subprocess
import time
import urllib.request

OUT_LINES = '/tmp/r1_census_lines.tsv'
LOG = '/tmp/r1_census.log'

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


def tag_index():
    d = json.loads(urllib.request.urlopen(
        'https://qyapi.weixin.qq.com/cgi-bin/externalcontact/get_corp_tag_list?access_token='
        + token(), timeout=30).read())
    grade_wids, wid2name = set(), {}
    for g in d.get('tag_group', []):
        for t in g.get('tag', []):
            wid2name[t['id']] = t.get('name', '')
            if g.get('group_name') == '年级':
                grade_wids.add(t['id'])
    return grade_wids, wid2name


def fetch(eid):
    """返回 (errcode, follow_user 列表)。带 token 过期重取与瞬时重试。"""
    for attempt in range(4):
        try:
            url = ('https://qyapi.weixin.qq.com/cgi-bin/externalcontact/get?external_userid='
                   + eid + '&access_token=' + token())
            r = json.loads(urllib.request.urlopen(url, timeout=30).read())
        except Exception as e:  # 网络抖动
            log('  网络异常 %s: %s' % (eid[-8:], e))
            time.sleep(3)
            continue
        ec = r.get('errcode')
        if ec == 42001 or ec == 40014:  # token 失效
            token(force=True)
            continue
        if ec == 45009:  # 频率超限
            log('  45009 限频，退避 30s')
            time.sleep(30)
            continue
        if ec in (0, None):
            return ec, r.get('follow_user', [])
        if ec == -1:
            time.sleep(2)
            continue
        return ec, r.get('follow_user', [])
    return -1, []


def main():
    grade_wids, wid2name = tag_index()
    log('=== R1 普查开始 %s 年级组 wid 数=%d ===' % (time.strftime('%F %T'), len(grade_wids)))

    # 客户 → 目标 wid（同一客户可能有多条 R1 行，取其一并记录多值告警）
    targets = {}
    for line in sql("SELECT external_userid, target_wecom_tag_id "
                    "FROM grade_tag_migration_progress "
                    "WHERE row_class='R1' AND external_userid IS NOT NULL AND external_userid<>''"):
        p = line.split('\t')
        if len(p) < 2 or not p[0]:
            continue
        if p[0] in targets and targets[p[0]] != p[1]:
            log('  注意：客户 %s 有多个 R1 目标 %s / %s' % (p[0][-8:], targets[p[0]], p[1]))
        targets.setdefault(p[0], p[1])

    done = set()
    if os.path.exists(OUT_LINES):
        for l in open(OUT_LINES, encoding='utf-8'):
            if l.strip():
                done.add(l.split('\t')[0])
    log('R1 客户 %d 人，已完成 %d，剩余 %d' % (len(targets), len(done), len(targets) - len(done)))

    n = 0
    for eid, wid in targets.items():
        if eid in done:
            continue
        ec, fus = fetch(eid)
        if ec == 84061:
            cls, uid, names = 'RELATION_GONE', '', ''
        elif ec not in (0, None):
            cls, uid, names = 'ERR%d' % ec, '', ''
        else:
            tag_ids = [tg.get('tag_id') for fu in fus for tg in fu.get('tags', [])]
            uid = next((fu.get('userid') for fu in fus if fu.get('userid')), '')
            names = ','.join(sorted({wid2name.get(t, t) for t in tag_ids if t in grade_wids}))
            if wid in tag_ids:
                cls = 'HAS_TARGET'
            elif names:
                cls = 'HAS_OTHER'
            else:
                cls = 'NO_GRADE'
        with open(OUT_LINES, 'a', encoding='utf-8') as f:
            f.write('%s\t%s\t%s\t%s\t%s\n' % (eid, cls, uid, wid, names))
        n += 1
        if n % 200 == 0:
            log('  进度 %d（本次新增）' % n)
        time.sleep(0.46)
        if n % 100 == 0:
            time.sleep(10)

    # 汇总
    cnt = {}
    for l in open(OUT_LINES, encoding='utf-8'):
        if l.strip():
            c = l.split('\t')[1]
            cnt[c] = cnt.get(c, 0) + 1
    log('=== 分类汇总 ===')
    for k in sorted(cnt, key=lambda x: -cnt[x]):
        log('  %-14s %d' % (k, cnt[k]))
    log('=== R1 普查结束 %s ===' % time.strftime('%F %T'))


if __name__ == '__main__':
    main()
