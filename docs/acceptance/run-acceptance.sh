#!/usr/bin/env bash
# F4 结算单状态机验收脚本：对运行中的服务（默认 :8080）逐步执行验收用例，
# 每步把「命令 + 真实响应」实录到 docs/acceptance/*.txt 并渲染截图 *.png。
# 用法：docs/acceptance/run-acceptance.sh [baseUrl]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
DIR="$(cd "$(dirname "$0")" && pwd)"
SUF="ACC$$"   # 本次验收的唯一后缀，避免与库中历史数据冲突
reqno() { echo "REQ-$SUF-$(date +%s%N)"; }   # 纳秒时间戳保证每次调用唯一（子shell 无法共享计数器）

# 执行一步：记录命令与响应到实录文件
# run <实录文件> <展示用命令> <实际命令...>
run() {
  local txt="$1"; local show="$2"; shift 2
  {
    echo "$ $show"
    "$@" | python3 -m json.tool --no-ensure-ascii
    echo
  } >> "$txt"
}

# 从 JSON 提取字段；失败或非对象时退出，避免空值继续串联
jv() { python3 -c "import sys,json;d=json.load(sys.stdin);assert d.get('data') is not None,d;print(d$2)" <<< "$1" || { echo "!! 提取字段失败: $1" >&2; exit 1; }; }

: > /dev/null
echo "== 验收环境：$BASE  后缀：$SUF"

# ---------- 步骤 0：准备数据（农机 + 两张已完工作业单） ----------
T="$DIR/00-prepare.txt"; : > "$T"
echo "$ curl -X POST $BASE/api/machines   # 农机建档" >> "$T"
MRESP=$(curl -s -X POST "$BASE/api/machines" -H 'Content-Type: application/json' -d "{
  \"machineCode\": \"M-$SUF\", \"machineName\": \"验收拖拉机01\", \"requestNo\": \"$(reqno)\"}")
echo "$MRESP" | python3 -m json.tool --no-ensure-ascii >> "$T"; echo >> "$T"
MID=$(jv "$MRESP" "['data']['id']")

mkorder() {  # mkorder <单号后缀> <轨迹面积> -> 作业单ID（已完工）
  local ono="WO-$SUF-$1"
  local oresp oid
  oresp=$(curl -s -X POST "$BASE/api/work-orders" -H 'Content-Type: application/json' -d "{
    \"orderNo\": \"$ono\", \"machineId\": $MID, \"operationType\": \"收割\", \"requestNo\": \"$(reqno)\"}")
  oid=$(jv "$oresp" "['data']['id']")   # 分开赋值，失败即中止（local 会吞掉退出码）
  curl -s -X POST "$BASE/api/work-orders/$oid/track-area" -H 'Content-Type: application/json' \
    -d "{\"trackArea\": $2, \"requestNo\": \"$(reqno)\"}" > /dev/null
  curl -s -X POST "$BASE/api/work-orders/$oid/transit" -H 'Content-Type: application/json' \
    -d "{\"targetStatus\": \"IN_PROGRESS\", \"requestNo\": \"$(reqno)\"}" > /dev/null
  curl -s -X POST "$BASE/api/work-orders/$oid/transit" -H 'Content-Type: application/json' \
    -d "{\"targetStatus\": \"COMPLETED\", \"requestNo\": \"$(reqno)\"}" > /dev/null
  echo "$oid"
}
OID1=$(mkorder 01 10.00)
OID2=$(mkorder 02 5.50)
cat >> "$T" <<EOF
$ # 作业单 WO-$SUF-01（轨迹面积 10.00 亩）、WO-$SUF-02（5.50 亩）已完工
$ curl "$BASE/api/work-orders?orderNo=WO-$SUF-01"
EOF
curl -s "$BASE/api/work-orders?orderNo=WO-$SUF-01" | python3 -c "
import sys,json; d=json.load(sys.stdin)['data']['order']
print(json.dumps({k:d[k] for k in ('orderNo','status','trackArea','version')}, ensure_ascii=False, indent=2))" >> "$T"

# ---------- 步骤 1：生成结算单 ----------
T="$DIR/01-generate.txt"; : > "$T"
GENJSON="{\"machineId\": $MID, \"unitPrice\": 100.00, \"operator\": \"张三\", \"requestNo\": \"$(reqno)\", \"remark\": \"9月收割结算\"}"
echo "$ curl -X POST $BASE/api/settlements/generate -d '$GENJSON'" >> "$T"
GRESP=$(curl -s -X POST "$BASE/api/settlements/generate" -H 'Content-Type: application/json' -d "$GENJSON")
echo "$GRESP" | python3 -m json.tool --no-ensure-ascii >> "$T"
SID=$(jv "$GRESP" "['data']['id']")

# ---------- 步骤 2：非法迁移即拒绝（DRAFT 直接付款） ----------
T="$DIR/02-illegal-jump.txt"; : > "$T"
BADJSON="{\"targetStatus\": \"PAID\", \"version\": 0, \"operator\": \"李四\", \"requestNo\": \"$(reqno)\"}"
echo "$ curl -X POST $BASE/api/settlements/$SID/transit -d '$BADJSON'" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID/transit" -H 'Content-Type: application/json' -d "$BADJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"
echo >> "$T"
echo "$ # 原地踏步 DRAFT→DRAFT 同样拒绝" >> "$T"
BAD2JSON="{\"targetStatus\": \"DRAFT\", \"version\": 0, \"operator\": \"李四\", \"requestNo\": \"$(reqno)\"}"
echo "$ curl -X POST $BASE/api/settlements/$SID/transit -d '$BAD2JSON'" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID/transit" -H 'Content-Type: application/json' -d "$BAD2JSON" | python3 -m json.tool --no-ensure-ascii >> "$T"

# ---------- 步骤 3：版本不符即拒绝 ----------
T="$DIR/03-stale-version.txt"; : > "$T"
STALEJSON="{\"targetStatus\": \"CONFIRMED\", \"version\": 9, \"operator\": \"李四\", \"requestNo\": \"$(reqno)\"}"
echo "$ curl -X POST $BASE/api/settlements/$SID/transit -d '$STALEJSON'" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID/transit" -H 'Content-Type: application/json' -d "$STALEJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"

# ---------- 步骤 4：正常流转 DRAFT→CONFIRMED→PAID（每次变更带版本与操作人） ----------
T="$DIR/04-normal-flow.txt"; : > "$T"
CJSON="{\"targetStatus\": \"CONFIRMED\", \"version\": 0, \"operator\": \"李四\", \"requestNo\": \"$(reqno)\", \"remark\": \"金额核对无误\"}"
echo "$ curl -X POST $BASE/api/settlements/$SID/transit -d '$CJSON'" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID/transit" -H 'Content-Type: application/json' -d "$CJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"
echo >> "$T"
PJSON="{\"targetStatus\": \"PAID\", \"version\": 1, \"operator\": \"王五\", \"requestNo\": \"$(reqno)\", \"remark\": \"已转账\"}"
echo "$ curl -X POST $BASE/api/settlements/$SID/transit -d '$PJSON'" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID/transit" -H 'Content-Type: application/json' -d "$PJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"
echo >> "$T"
echo "$ # 终态 PAID 后再流转：拒绝" >> "$T"
TJSON="{\"targetStatus\": \"VOID\", \"version\": 2, \"operator\": \"王五\", \"requestNo\": \"$(reqno)\"}"
echo "$ curl -X POST $BASE/api/settlements/$SID/transit -d '$TJSON'" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID/transit" -H 'Content-Type: application/json' -d "$TJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"

# ---------- 步骤 5：流转历史完整（前后状态/前后版本/操作人/请求号） ----------
T="$DIR/05-history.txt"; : > "$T"
echo "$ curl $BASE/api/settlements/$SID" >> "$T"
curl -s "$BASE/api/settlements/$SID" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
o=d['order']
print('结算单:', json.dumps({k:o[k] for k in ('settleNo','status','orderCount','totalArea','unitPrice','totalAmount','version')}, ensure_ascii=False, indent=2))
print('明细:')
for it in d['items']:
    print(' ', json.dumps({k:it[k] for k in ('orderNo','trackArea','unitPrice','amount')}, ensure_ascii=False))
print('流转历史:')
for l in d['statusLogs']:
    print(' ', json.dumps({k:l[k] for k in ('fromStatus','toStatus','fromVersion','toVersion','operator','requestNo','createTime')}, ensure_ascii=False))" >> "$T"

# ---------- 步骤 6：迁移规则配置化（在线停用 DRAFT→VOID） ----------
T="$DIR/06-rule-config.txt"; : > "$T"
echo "$ curl $BASE/api/settle-rules" >> "$T"
curl -s "$BASE/api/settle-rules" | python3 -c "
import sys,json
for r in json.load(sys.stdin)['data']:
    print(' ', json.dumps({k:r[k] for k in ('id','fromStatus','toStatus','enabled','remark')}, ensure_ascii=False))" >> "$T"
echo >> "$T"
TOGGLEJSON="{\"enabled\": false, \"operator\": \"管理员\", \"requestNo\": \"$(reqno)\", \"remark\": \"月底封账，暂停作废\"}"
echo "$ curl -X POST $BASE/api/settle-rules/2/toggle -d '$TOGGLEJSON'" >> "$T"
curl -s -X POST "$BASE/api/settle-rules/2/toggle" -H 'Content-Type: application/json' -d "$TOGGLEJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"

# ---------- 步骤 7：规则即刻生效（停用拒绝 / 启用放行） ----------
# 另起一台农机 + 一张完工单，生成第二张结算单用于作废演示
M2RESP=$(curl -s -X POST "$BASE/api/machines" -H 'Content-Type: application/json' -d "{
  \"machineCode\": \"M-$SUF-B\", \"machineName\": \"验收拖拉机02\", \"requestNo\": \"$(reqno)\"}")
MID2=$(jv "$M2RESP" "['data']['id']")
O2RESP=$(curl -s -X POST "$BASE/api/work-orders" -H 'Content-Type: application/json' -d "{
  \"orderNo\": \"WO-$SUF-B1\", \"machineId\": $MID2, \"operationType\": \"播种\", \"requestNo\": \"$(reqno)\"}")
OIDB=$(jv "$O2RESP" "['data']['id']")
curl -s -X POST "$BASE/api/work-orders/$OIDB/track-area" -H 'Content-Type: application/json' \
  -d "{\"trackArea\": 3.00, \"requestNo\": \"$(reqno)\"}" > /dev/null
curl -s -X POST "$BASE/api/work-orders/$OIDB/transit" -H 'Content-Type: application/json' \
  -d "{\"targetStatus\": \"IN_PROGRESS\", \"requestNo\": \"$(reqno)\"}" > /dev/null
curl -s -X POST "$BASE/api/work-orders/$OIDB/transit" -H 'Content-Type: application/json' \
  -d "{\"targetStatus\": \"COMPLETED\", \"requestNo\": \"$(reqno)\"}" > /dev/null
G2RESP=$(curl -s -X POST "$BASE/api/settlements/generate" -H 'Content-Type: application/json' \
  -d "{\"machineId\": $MID2, \"unitPrice\": 90.00, \"operator\": \"张三\", \"requestNo\": \"$(reqno)\"}")
SID2=$(jv "$G2RESP" "['data']['id']")

T="$DIR/07-rule-effect.txt"; : > "$T"
VJSON="{\"targetStatus\": \"VOID\", \"version\": 0, \"operator\": \"张三\", \"requestNo\": \"$(reqno)\"}"
echo "$ # 规则已停用：DRAFT→VOID 拒绝" >> "$T"
echo "$ curl -X POST $BASE/api/settlements/$SID2/transit -d '$VJSON'" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID2/transit" -H 'Content-Type: application/json' -d "$VJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"
echo >> "$T"
REJSON="{\"enabled\": true, \"operator\": \"管理员\", \"requestNo\": \"$(reqno)\"}"
echo "$ curl -X POST $BASE/api/settle-rules/2/toggle -d '$REJSON'   # 重新启用" >> "$T"
curl -s -X POST "$BASE/api/settle-rules/2/toggle" -H 'Content-Type: application/json' -d "$REJSON" | python3 -m json.tool --no-ensure-ascii >> "$T"
echo >> "$T"
V2JSON="{\"targetStatus\": \"VOID\", \"version\": 0, \"operator\": \"张三\", \"requestNo\": \"$(reqno)\", \"remark\": \"单价录错，作废重开\"}"
echo "$ curl -X POST $BASE/api/settlements/$SID2/transit -d '$V2JSON'   # 启用后放行" >> "$T"
curl -s -X POST "$BASE/api/settlements/$SID2/transit" -H 'Content-Type: application/json' -d "$V2JSON" | python3 -m json.tool --no-ensure-ascii >> "$T"

# ---------- 步骤 8：作废释放作业单，可重新结算 ----------
T="$DIR/08-void-release.txt"; : > "$T"
echo "$ curl $BASE/api/work-orders?orderNo=WO-$SUF-B1   # 作废后 settleId 已释放" >> "$T"
curl -s "$BASE/api/work-orders?orderNo=WO-$SUF-B1" | python3 -c "
import sys,json; d=json.load(sys.stdin)['data']['order']
print(json.dumps({k:d[k] for k in ('orderNo','status','settleId')}, ensure_ascii=False, indent=2))" >> "$T"
echo >> "$T"
G3JSON="{\"machineId\": $MID2, \"unitPrice\": 95.00, \"operator\": \"张三\", \"requestNo\": \"$(reqno)\", \"remark\": \"修正单价重新结算\"}"
echo "$ curl -X POST $BASE/api/settlements/generate -d '$G3JSON'   # 释放后重新结算成功" >> "$T"
curl -s -X POST "$BASE/api/settlements/generate" -H 'Content-Type: application/json' -d "$G3JSON" | python3 -m json.tool --no-ensure-ascii >> "$T"

# ---------- 渲染截图 ----------
for f in "$DIR"/*.txt; do
  name=$(basename "$f" .txt)
  title=$(head -1 "$f" | sed 's/^\$ //' | cut -c1-60)
  case "$name" in
    00-prepare)        title="步骤0 准备数据：农机建档 + 两张作业单完工";;
    01-generate)       title="步骤1 生成结算单：完工单汇总快照，状态 DRAFT";;
    02-illegal-jump)   title="步骤2 非法迁移即拒绝：DRAFT 不能直接 PAID";;
    03-stale-version)  title="步骤3 版本不符即拒绝：提交版本须与当前一致";;
    04-normal-flow)    title="步骤4 正常流转：确认→付款，版本随变更递增";;
    05-history)        title="步骤5 流转历史完整：前后状态/版本/操作人/请求号";;
    06-rule-config)    title="步骤6 迁移规则配置化：在线停用 DRAFT→VOID";;
    07-rule-effect)    title="步骤7 规则即刻生效：停用拒绝 / 启用放行";;
    08-void-release)   title="步骤8 作废释放作业单：可重新生成结算单";;
  esac
  "$DIR/shot.sh" "$DIR/$name.png" "$title" "$f" > /dev/null
done
echo "== 验收完成：$DIR 下 9 张截图已生成"
