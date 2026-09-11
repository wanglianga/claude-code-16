#!/bin/bash
# 冒烟测试：走通关键业务流（需先 docker compose up -d）
set -u
COMPOSE="docker compose"
[ -n "${COMPOSE_PROJECT_NAME:-}" ] && COMPOSE="docker compose -p ${COMPOSE_PROJECT_NAME}"
BASE="http://host.docker.internal:$($COMPOSE port app 8080 | cut -d: -f2)"
J=/tmp/cookies; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL  $1"; }
check() { if grep -q "$3" "$2"; then ok "$1"; else bad "$1 (未找到: $3)"; fi; }
login() {
  curl -s -c "$3" -b "$3" -o /dev/null -w "%{http_code}" -X POST "$BASE/login" \
    --data-urlencode "username=$1" --data-urlencode "password=$2" | grep -q 302 && ok "登录 $1" || bad "登录 $1"
}
rm -f $J-*
# 上传用测试附件
printf '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="60"><rect width="100" height="60" fill="#eee"/><text x="10" y="35">seal</text></svg>' > /tmp/seal.svg
printf '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="60"><rect width="100" height="60" fill="#dfd"/><text x="10" y="35">new</text></svg>' > /tmp/seal2.svg
printf '\xff\xd8\xff\xe0fakejpegdata\xff\xd9' > /tmp/site.jpg

echo "== 1. 各角色登录 =="
login regulator gov123 $J-reg
login admin market123 $J-admin
login vendor2 vendor123 $J-v2
login consumer consumer123 $J-c1

echo "== 2. 市场管理方：台账与登记 =="
curl -s -b $J-admin "$BASE/admin/scales" -o /tmp/t.html; check "台账列表" /tmp/t.html "DEV-0002"
curl -s -b $J-admin -X POST "$BASE/admin/scales/new" \
  -F deviceNo=DEV-1001 -F stallId=1 -F category=肉类 -F certNo=检字2026-NEW -F certValidUntil=2027-06-30 \
  -F "sealPhoto=@/tmp/seal.svg;type=image/svg+xml" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "登记新秤(含封签照片)" || bad "登记新秤"
curl -s -b $J-admin "$BASE/admin/scales" -o /tmp/t.html; check "新秤入台账" /tmp/t.html "DEV-1001"
curl -s -b $J-admin -X POST "$BASE/admin/scales/1/toggle-online" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "切换联网状态" || bad "切换联网状态"
curl -s -b $J-admin -X POST "$BASE/admin/scales/1/toggle-online" -o /dev/null # 恢复

echo "== 3. 监管：任务生成与抽检超标处置 =="
curl -s -b $J-reg "$BASE/reg/dashboard" -o /tmp/t.html; check "工作台" /tmp/t.html "规则引擎"
curl -s -b $J-reg -X POST "$BASE/reg/tasks/generate" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "生成抽检任务" || bad "生成抽检任务"
curl -s -b $J-reg "$BASE/reg/tasks" -o /tmp/tasks.html; check "任务列表" /tmp/tasks.html "待办"
TID=$(grep -o 'taskId=[0-9]*' /tmp/tasks.html | head -1 | cut -d= -f2)
if [ -n "$TID" ]; then ok "取得待办任务 #$TID"; else bad "取得待办任务"; fi
curl -s -b $J-reg "$BASE/reg/inspections/new?taskId=$TID" -o /tmp/t.html; check "抽检录入表单" /tmp/t.html "标准砝码"
curl -s -b $J-reg -X POST "$BASE/reg/inspections/new" \
  -F taskId=$TID -F standardWeightG=1000 -F displayedWeightG=1060 -F sealStatus=BROKEN -F vendorConfirmed=on \
  -F "photo=@/tmp/site.jpg;type=image/jpeg" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "录入超标抽检" || bad "录入超标抽检"
curl -s -b $J-reg "$BASE/reg/penalties" -o /tmp/pen.html; check "超标自动处罚+停用" /tmp/pen.html "显示 1060g"

echo "== 4. 消费者投诉 → 可信度判断 → 核验 → 处罚确认 =="
curl -s -b $J-c1 -X POST "$BASE/consumer/complaints/new" \
  -F stallId=4 -F purchaseTime="2026-09-10T09:30" -F product=苹果 -F nominalWeightG=2000 -F reweighedWeightG=1900 \
  -F paymentRef=WX20260910TEST -F "photo=@/tmp/site.jpg;type=image/jpeg" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "提交投诉" || bad "提交投诉"
curl -s -b $J-c1 "$BASE/consumer/complaints" -o /tmp/t.html; check "投诉可信度标记(共用秤)" /tmp/t.html "多人共用"
curl -s -b $J-reg "$BASE/reg/complaints" -o /tmp/comp.html; check "监管看到投诉" /tmp/comp.html "苹果"
CID=$(grep -o '/reg/complaints/[0-9]*/verify' /tmp/comp.html | head -1 | grep -o '[0-9]*')
if [ -n "$CID" ]; then ok "取得待核验投诉 #$CID"; else bad "取得待核验投诉"; fi
curl -s -b $J-reg -X POST "$BASE/reg/complaints/$CID/verify" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "投诉核验属实" || bad "投诉核验"
curl -s -b $J-reg "$BASE/reg/penalties" -o /tmp/pen.html
PID=$(grep -o '/reg/penalties/[0-9]*/confirm' /tmp/pen.html | head -1 | grep -o '[0-9]*')
curl -s -b $J-reg -X POST "$BASE/reg/penalties/$PID/confirm" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "处罚确认(信用/公示联动)" || bad "处罚确认"
curl -s -b $J-reg "$BASE/reg/disclosures" -o /tmp/t.html; check "公示已发布" /tmp/t.html "公示中"

echo "== 5. 摊主：确认抽检 / 申诉 / 整改 =="
curl -s -b $J-v2 "$BASE/vendor/home" -o /tmp/vh.html; check "摊主首页" /tmp/vh.html "我的处罚"
curl -s -b $J-v2 -X POST "$BASE/vendor/penalties/2/appeal" --data-urlencode "content=秤已送检，请求复核" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "摊主申诉" || bad "摊主申诉"
curl -s -b $J-reg "$BASE/reg/penalties" -o /tmp/pen.html; check "监管看到申诉" /tmp/pen.html "申诉"
AID=$(grep -o '/reg/appeals/[0-9]*/resolve' /tmp/pen.html | head -1 | grep -o '[0-9]*')
curl -s -b $J-reg -X POST "$BASE/reg/appeals/$AID/resolve" --data-urlencode "decision=REJECTED" --data-urlencode "resolution=证据充分，维持原处罚" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "申诉处理" || bad "申诉处理"

echo "== 6. 整改→复检→封签更换→档案闭环 =="
curl -s -b $J-v2 -X POST "$BASE/vendor/penalties/1/rectify" --data-urlencode "description=已重新校准并更换封签" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "提交整改" || bad "提交整改"
curl -s -b $J-reg "$BASE/reg/tasks" -o /tmp/tasks.html
RTID=$(awk 'BEGIN{RS="<tr"} /申请复检/ {if (match($0, /taskId=[0-9]+/)) {print substr($0, RSTART+7, RLENGTH-7); exit}}' /tmp/tasks.html)
if [ -n "$RTID" ]; then ok "取得复检任务 #$RTID"; else bad "取得复检任务"; fi
curl -s -b $J-reg -X POST "$BASE/reg/inspections/new" \
  -F taskId=$RTID -F standardWeightG=1000 -F displayedWeightG=1001 -F sealStatus=REPLACED -F vendorConfirmed=on \
  -F "photo=@/tmp/seal2.svg;type=image/svg+xml" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "复检合格录入" || bad "复检合格录入"
curl -s -b $J-reg "$BASE/reg/scales/2" -o /tmp/arch.html
check "档案:复检通过" /tmp/arch.html "复检通过"
check "档案:封签更换" /tmp/arch.html "封签更换"
check "档案:恢复使用" /tmp/arch.html "恢复使用"
check "档案:投诉属实事件" /tmp/arch.html "投诉属实"

echo "== 7. 报表与公开页 =="
curl -s -b $J-reg "$BASE/reg/reports/tracking?dim=stall" -o /tmp/t.html; check "处罚后变化追踪" /tmp/t.html "处罚前"
curl -s -b $J-reg "$BASE/reg/reports/tracking?dim=complainant" -o /tmp/t.html; check "投诉人维度" /tmp/t.html "投诉人"
curl -s -b $J-reg "$BASE/reg/reports/annual?year=2026" -o /tmp/t.html; check "年度治理材料" /tmp/t.html "年度短斤少两治理材料"
curl -s "$BASE/disclosures" -o /tmp/t.html; check "公示栏(免登录)" /tmp/t.html "公示"
curl -s "$BASE/health" -o /tmp/t.html; check "健康检查" /tmp/t.html '"db":true'

echo "== 8. 离线交易补传 =="
Y=$(date -d 'yesterday' +%F)
# 8.1 离线窗口内先产生一起投诉（摊位 A-03=stallId 3，DEV-0003 离线）
curl -s -b $J-c1 -X POST "$BASE/consumer/complaints/new" \
  -F stallId=3 -F purchaseTime="${Y}T08:20" -F product=西红柿 -F nominalWeightG=1000 -F reweighedWeightG=950 \
  -F paymentRef=WX-OFFLINE-1 -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "离线期间投诉" || bad "离线期间投诉"
# 8.2 本摊位摊主可访问补传表单
curl -s -c $J-v3 -b $J-v3 -o /dev/null -X POST "$BASE/login" --data-urlencode "username=vendor3" --data-urlencode "password=vendor123"
curl -s -b $J-v3 "$BASE/scales/3/offline-sync" -o /tmp/t.html; check "摊主补传表单" /tmp/t.html "本地流水"
# 8.3 提交补传：窗口集中在早高峰 07:00-09:30，设备时钟拨快 45 分钟
LOC=$(curl -s -b $J-v3 -D - -o /dev/null -X POST "$BASE/scales/3/offline-sync" \
  --data-urlencode "offlineStart=${Y}T07:00" \
  --data-urlencode "offlineEnd=${Y}T09:30" \
  --data-urlencode "deviceClock=$(date -d '+45 min' +%FT%H:%M)" \
  --data-urlencode "sealAtSync=INTACT" \
  --data-urlencode "csv=${Y} 07:15,1250,45.00
${Y} 07:42,860,30.96
${Y} 08:10,2100,75.60
${Y} 08:47,640,23.04
${Y} 09:12,1500,54.00" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
BID=$(echo "$LOC" | grep -o '[0-9]*$')
[ -n "$BID" ] && ok "补传提交(批次#$BID)" || bad "补传提交"
# 8.4 批次详情：异常标记 + 人工复核 + 补做抽检提示
curl -s -b $J-reg "$BASE$LOC" -o /tmp/batch.html
check "高峰期异常集中标记" /tmp/batch.html "高峰期离线集中"
check "时钟漂移标记" /tmp/batch.html "设备时钟漂移"
check "交易进入人工复核" /tmp/batch.html "待人工复核"
check "建议补做抽检提示" /tmp/batch.html "补做抽检"
# 8.5 重点巡检任务生成
curl -s -b $J-reg "$BASE/reg/tasks" -o /tmp/t.html; check "重点巡检任务" /tmp/t.html "重点巡检"
# 8.6 摊位信用扣减（A-03：100 → 95）
curl -s -b $J-admin "$BASE/admin/stalls" -o /tmp/t.html
awk 'BEGIN{RS="<tr"} /A-03/ && /95/' /tmp/t.html >/dev/null && ok "摊位信用扣减(95)" || bad "摊位信用扣减"
# 8.7 监管复核队列 → 复核通过
curl -s -b $J-reg "$BASE/reg/offline" -o /tmp/off.html; check "复核队列" /tmp/off.html "待复核"
curl -s -b $J-reg -X POST "$BASE/reg/offline/$BID/review" --data-urlencode "decision=OK" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "人工复核通过" || bad "人工复核"
curl -s -b $J-reg "$BASE$LOC" -o /tmp/batch.html; check "复核后交易状态" /tmp/batch.html "复核通过"
# 8.8 越权：消费者访问补传表单
CODE=$(curl -s -o /dev/null -w "%{http_code}" -b $J-c1 "$BASE/scales/3/offline-sync")
[ "$CODE" = "403" ] && ok "消费者访问补传被拒" || bad "补传越权(code=$CODE)"

echo "== 9. 多人共用秤责任拆分 =="
# 9.1 市场管理方：排班与收款码管理页
curl -s -b $J-admin "$BASE/admin/sharing" -o /tmp/sh.html
check "共用秤排班页" /tmp/sh.html "早晚市排班"
check "排班:DEV-0004晚市" /tmp/sh.html "晚市"
check "收款码前缀登记" /tmp/sh.html "ZFB-S5-"
# 9.2 消费者就晚市 B-02 禽蛋摊位提交投诉（共用秤 DEV-0004，付款码归属 B-02）
Y2=$(date -d 'yesterday' +%F)
curl -s -b $J-c1 -X POST "$BASE/consumer/complaints/new" \
  -F stallId=5 -F purchaseTime="${Y2}T18:20" -F product=土鸡蛋 -F nominalWeightG=1000 -F reweighedWeightG=910 \
  -F paymentRef=ZFB-S5-TEST-001 -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "提交共用秤晚市投诉" || bad "提交共用秤晚市投诉"
curl -s -b $J-c1 "$BASE/consumer/complaints" -o /tmp/t.html; check "消费者看到共用秤可信度提示" /tmp/t.html "多人共用"
# 9.3 监管核验：不落直接处罚，生成责任认定单（投诉 id 取自消费者本人列表中「#id 土鸡蛋」标题）
RCID_RAW=$(curl -s -b $J-c1 "$BASE/consumer/complaints" | grep -oE '#[0-9]+ 土鸡蛋' | head -1 | grep -oE '[0-9]+')
[ -n "$RCID_RAW" ] && ok "定位待核验共用秤投诉 #$RCID_RAW" || bad "定位待核验共用秤投诉"
LOC=$(curl -s -b $J-reg -D - -o /dev/null -X POST "$BASE/reg/complaints/$RCID_RAW/verify" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
RSP=$(echo "$LOC" | grep -oE 'responsibility/[0-9]+' | grep -oE '[0-9]+')
echo "$LOC" | grep -q '/reg/responsibility/' && ok "核验转入责任认定(#$RSP)" || bad "核验转入责任认定($LOC)"
# 9.4 认定页：五类证据聚合 + 建议实际经营者 B-02 + 备案摊位 B-01
curl -s -b $J-reg "$BASE/reg/responsibility/$RSP" -o /tmp/rsp.html
check "证据:付款码" /tmp/rsp.html "付款码"
check "证据:摊位排班" /tmp/rsp.html "摊位排班"
check "证据:交易流水" /tmp/rsp.html "交易流水"
check "证据:商品品类" /tmp/rsp.html "商品品类"
check "建议实际经营者B-02" /tmp/rsp.html "B-02"
check "展示设备备案摊位B-01" /tmp/rsp.html "B-01"
check "认定拆分说明" /tmp/rsp.html "分别生成"
# 责任明确前投诉为「责任认定中」
curl -s -b $J-reg "$BASE/reg/complaints" -o /tmp/comp.html
awk 'BEGIN{RS="<div"} /土鸡蛋/ && /责任认定中/' /tmp/comp.html >/dev/null && ok "责任明确前投诉挂起(责任认定中)" || bad "投诉挂起状态"
# 9.5 监管认定：实际经营者 B-02(stallId=5)，同时认定设备管理责任，附监控备注
curl -s -b $J-reg -X POST "$BASE/reg/responsibility/$RSP/decide" \
  --data-urlencode "operatorStallId=5" --data-urlencode "deviceFault=on" \
  --data-urlencode "monitorNote=晚市监控显示 18:20 为 B-02 摊位使用 DEV-0004 称重收款" \
  -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "提交责任认定拆分" || bad "提交责任认定拆分"
# 9.6 两条处罚分别记录：经营短斤责任(B-02) + 设备管理责任(B-01)
curl -s -b $J-reg "$BASE/reg/penalties" -o /tmp/pen.html
check "经营短斤责任处罚" /tmp/pen.html "经营短斤责任"
check "设备管理责任处罚" /tmp/pen.html "设备管理责任"
awk 'BEGIN{RS="<tr"} /设备管理责任/ && /B-01/' /tmp/pen.html >/dev/null && ok "设备责任归备案摊位B-01" || bad "设备责任归属"
awk 'BEGIN{RS="<tr"} /经营短斤责任/ && /B-02/' /tmp/pen.html >/dev/null && ok "经营责任归实际经营者B-02" || bad "经营责任归属"
# 9.7 同组一并确认
CONF=$(grep -o '/reg/penalties/[0-9]*/confirm' /tmp/pen.html | head -1 | grep -o '[0-9]*')
curl -s -b $J-reg -X POST "$BASE/reg/penalties/$CONF/confirm" -o /dev/null -w "%{http_code}\n" | grep -q 302 && ok "同组处罚整组确认" || bad "同组处罚确认"
curl -s -b $J-reg "$BASE/reg/responsibility/$RSP" -o /tmp/rsp2.html
N=$(grep -o '已确认' /tmp/rsp2.html | wc -l)
[ "$N" -ge 2 ] && ok "拆分处罚均已确认($N 条)" || bad "拆分处罚确认状态($N)"
# 9.8 公开公示栏区分两类问题（精确校验 badge 标记，而非标题文字）
curl -s "$BASE/disclosures" -o /tmp/pub.html
grep -q 'badge b-yellow">设备管理问题</span>' /tmp/pub.html && ok "公开页:设备问题badge" || bad "公开页:设备问题badge"
grep -q 'badge b-red">经营行为问题</span>' /tmp/pub.html && ok "公开页:经营问题badge" || bad "公开页:经营问题badge"
check "公示:认定实际经营摊位说明" /tmp/pub.html "实际经营摊位"
# 9.9 责任认定队列状态 + 秤档案共用排班
curl -s -b $J-reg "$BASE/reg/responsibility" -o /tmp/t.html; check "责任队列:已认定拆分" /tmp/t.html "已认定拆分"
curl -s -b $J-reg "$BASE/reg/scales/4" -o /tmp/arch.html; check "档案:多人共用排班" /tmp/arch.html "多人共用排班"
check "档案:责任拆分事件" /tmp/arch.html "责任拆分认定"
# 9.10 经营责任摊位 B-02 摊主可看到经营处罚（vendor2），设备责任不影响其申诉入口以外的视图
curl -s -b $J-v2 "$BASE/vendor/home" -o /tmp/vh.html; check "摊主看到责任类型标签" /tmp/vh.html "经营短斤责任"
# 9.11 新增一条相邻摊位排班（登记后自动保持共用标记）
curl -s -b $J-admin -X POST "$BASE/admin/sharing/schedules/new" \
  --data-urlencode scaleId=4 --data-urlencode stallId=6 --data-urlencode slot=EVENING \
  --data-urlencode startHour=18 --data-urlencode endHour=19 -o /dev/null -w "%{http_code}\n" | grep -q 302 \
  && ok "登记相邻摊位共用排班" || bad "登记共用排班"
curl -s -b $J-admin "$BASE/admin/sharing" -o /tmp/sh.html; check "新排班入册(B-03)" /tmp/sh.html "B-03"

echo "== 10. 权限边界 =="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -b $J-c1 "$BASE/reg/dashboard")
[ "$CODE" = "403" ] && ok "消费者访问监管页被拒(403)" || bad "越权检查(code=$CODE)"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/reg/dashboard")
[ "$CODE" = "302" ] && ok "未登录跳转登录页" || bad "未登录跳转(code=$CODE)"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -b $J-c1 "$BASE/reg/responsibility")
[ "$CODE" = "403" ] && ok "消费者访问责任认定被拒(403)" || bad "责任认定越权(code=$CODE)"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -b $J-v2 -X POST "$BASE/admin/sharing/schedules/new" --data "scaleId=4&stallId=6&slot=EVENING&startHour=18&endHour=19")
[ "$CODE" = "403" ] && ok "摊主不能登记排班(403)" || bad "排班越权(code=$CODE)"

echo
echo "结果: PASS=$PASS FAIL=$FAIL"
exit $FAIL
