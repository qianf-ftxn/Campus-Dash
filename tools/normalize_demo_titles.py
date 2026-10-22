#!/usr/bin/env python3
"""Rename recognizable fixture titles in-place. No order/ledger deletion or financial updates.
Run without --apply to preview. MYSQL_PWD is passed to the container via environment.
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apply', action='store_true')
parser.add_argument('--container', default='campus-dash-mysql')
parser.add_argument('--redis-container', default='campus-dash-redis')
parser.add_argument('--backup-dir', type=Path)
args = parser.parse_args()
if not os.environ.get('MYSQL_PWD'):
    parser.error('Set MYSQL_PWD for the local demo database before running.')
base = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', args.container, 'mysql',
        '--default-character-set=utf8mb4', '-uroot', '--batch', '--raw', '--skip-column-names', '--unbuffered', 'campus_dash']
def query(sql):
    return subprocess.run(base, input=sql, text=True, capture_output=True, check=True).stdout
rows = [json.loads(line) for line in query("SELECT JSON_OBJECT('id',CAST(id AS CHAR),'title',title,'type',type) FROM errand ORDER BY created_at,id;").splitlines()]
fixture = re.compile(r'bench|cache|credit|api_it|cursor|settle|refund|perm|test|对照|校验|验证|验收|启动|自查回归|^[0-9a-f_-]+$', re.I)
places = ['竹园一号楼', '松园二号楼', '桂园三号楼', '荷园四号楼', '梅园五号楼', '兰园六号楼',
          '图书馆东门', '教学楼大厅', '实验楼门口', '体育馆前台', '研究生公寓', '学生服务中心',
          '艺术楼一层', '计算机楼大厅', '文科楼门口', '创新楼前台']
delivery = [
 ('帮取快递', '东门菜鸟驿站取件，送到{}'),
 ('取一份打印资料', '文印中心取件，送到{}'),
 ('帮带午餐', '第一食堂取餐，送到{}'),
 ('送一份课程讲义', '公共教学楼取件，送到{}'),
 ('帮拿外卖', '南门外卖柜取餐，送到{}'),
 ('取一箱饮用水', '校园超市取货，送到{}'),
 ('帮取洗好的衣服', '学生洗衣房取件，送到{}'),
 ('送一份社团海报', '社团活动中心取件，送到{}'),
 ('帮取图书', '图书馆服务台取书，送到{}'),
 ('送一份实验报告', '理科楼大厅取件，送到{}'),
 ('帮带晚餐', '第二食堂取餐，送到{}'),
 ('取一份报名材料', '学生事务大厅取件，送到{}'),
 ('送一个充电器', '青年公寓门口取件，送到{}'),
 ('帮取生日蛋糕', '校园烘焙店取货，送到{}'),
 ('送一套活动道具', '大学生活动中心取件，送到{}'),
 ('帮带早餐', '早餐窗口取餐，送到{}'),
]
buy = [('帮买文具', '校园超市买中性笔，送到{}'), ('帮买水果', '水果店买苹果和香蕉，送到{}'),
       ('帮买生活用品', '校园便利店买纸巾，送到{}'), ('帮买咖啡', '图书馆咖啡店下单，送到{}')]
plan = []
used = {row['title'] for row in rows if not fixture.search(row['title'])}
indices = {}
for row in rows:
    if not fixture.search(row['title']):
        continue
    kind = row['type']
    index = indices.get(kind, 0)
    while True:
        templates = buy if kind == 'BUY' else delivery
        heading, description = templates[index % len(templates)]
        title = heading + '｜' + description.format(places[(index * 7 + index // len(templates)) % len(places)])
        index += 1
        if title not in used:
            break
        if index > 4096:
            raise RuntimeError('Need additional natural task templates before applying this dataset.')
    assert len(title) <= 64
    used.add(title); indices[kind] = index
    plan.append({'id': row['id'], 'type': kind, 'before': row['title'], 'after': title})
print(json.dumps({'orders': len(rows), 'renames': len(plan), 'preview': plan[:8]}, ensure_ascii=False, indent=2))
if not args.apply or not plan:
    raise SystemExit(0)
backup = args.backup_dir or Path(__file__).resolve().parents[2] / '.local-backups' / ('task-titles-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S'))
backup.mkdir(parents=True, exist_ok=False, mode=0o700)
with (backup / 'campus-dash-before.sql').open('wb') as file:
    subprocess.run(['docker', 'exec', '-e', 'MYSQL_PWD', args.container, 'mysqldump', '-uroot',
                    '--single-transaction', '--no-tablespaces', '--set-gtid-purged=OFF', '--default-character-set=utf8mb4', 'campus_dash'], stdout=file, check=True)
(backup / 'title-changes.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2))
def sql_text(value):
    return 'CONVERT(0x' + value.encode('utf-8').hex() + ' USING utf8mb4)'
updates = ['START TRANSACTION;', 'SET @changed = 0;']
rollback = ['START TRANSACTION;']
for row in plan:
    assert row['id'].isdigit()
    updates += [f"UPDATE errand SET title={sql_text(row['after'])},updated_at=updated_at WHERE id={row['id']} AND BINARY title=BINARY {sql_text(row['before'])};", 'SET @changed = @changed + ROW_COUNT();']
    rollback += [f"UPDATE errand SET title={sql_text(row['before'])},updated_at=updated_at WHERE id={row['id']} AND BINARY title=BINARY {sql_text(row['after'])};"]
rollback += ['COMMIT;']
(backup / 'restore-titles.sql').write_text('\n'.join(rollback))
for file in backup.iterdir():
    file.chmod(0o600)
process = subprocess.Popen(base, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
try:
    process.stdin.write('\n'.join(updates) + "\nSELECT CONCAT('CHANGED:',@changed);\n")
    process.stdin.flush()
    result = process.stdout.readline().strip()
    if result != f'CHANGED:{len(plan)}':
        process.communicate('ROLLBACK;\n', timeout=30)
        raise RuntimeError(f'Title data changed concurrently; rolled back ({result}).')
    _, errors = process.communicate('COMMIT;\n', timeout=30)
    if process.returncode:
        raise RuntimeError(errors)
except BaseException:
    if process.poll() is None:
        process.kill(); process.wait()
    raise
# Invalidate only renamed detail shards; leave grab slots, sessions, and financial caches intact.
output = subprocess.run(['docker', 'exec', args.redis_container, 'redis-cli', '--scan', '--pattern', 'errand:detail:*'], text=True, capture_output=True, check=True).stdout
changed_ids = {row['id'] for row in plan}
keys = [key for key in output.splitlines() if len(key.split(':')) == 4 and key.split(':')[2] in changed_ids]
if keys:
    subprocess.run(['docker', 'exec', args.redis_container, 'redis-cli', 'DEL', *keys], capture_output=True, check=True)
remaining = [json.loads(line) for line in query("SELECT JSON_OBJECT('id',CAST(id AS CHAR),'title',title) FROM errand;").splitlines()]
assert len(remaining) == len(rows), 'Order count changed concurrently; inspect snapshot.'
assert not any(fixture.search(row['title']) for row in remaining), 'Some fixture titles remain.'
print(json.dumps({'applied': len(plan), 'order_count': len(remaining), 'backup': str(backup), 'invalidated_detail_keys': len(keys)}, ensure_ascii=False))
