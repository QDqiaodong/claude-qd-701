<template>
  <div class="release-page">
    <!-- 顶部：当前飞机放行总览（同步待审 / 已失效 / 已放行数量） -->
    <section class="panel">
      <div class="panel-head">
        <h3>出厂放行总览 · {{ current ? current.tailNo + ' · ' + current.model : '未选择飞机' }}</h3>
        <span class="panel-sub">阻断条件全部由服务端判定；刷新、重进、切换机号均以服务端事实为准</span>
        <div class="panel-extra">
          <el-button size="small" @click="loadAll">刷新</el-button>
        </div>
      </div>

      <div class="rel-overview">
        <div class="rel-stat"><b>{{ ov.pendingCount || 0 }}</b><span>全部待审</span></div>
        <div class="rel-stat"><b>{{ ov.invalidCount || 0 }}</b><span>全部已失效</span></div>
        <div class="rel-stat"><b>{{ ov.releasedCount || 0 }}</b><span>全部已放行</span></div>
        <div class="rel-stat is-warn"><b>{{ ov.aircraftPendingCount || 0 }}</b><span>本机待审</span></div>
        <div class="rel-stat is-danger"><b>{{ ov.aircraftInvalidCount || 0 }}</b><span>本机已失效</span></div>
        <div class="rel-stat is-ok"><b>{{ ov.aircraftReleasedCount || 0 }}</b><span>本机已放行</span></div>
      </div>

      <!-- 当前飞机事实四件套 + 发起门禁 -->
      <div v-if="gate" class="gate-grid">
        <div class="gate-item" :class="gate.airworthyOk ? 'is-ok' : 'is-bad'">
          <span>适航状态</span>
          <b>{{ gate.airworthyName }}</b>
          <em>{{ gate.airworthyOk ? '满足' : '非适航，阻断' }}</em>
        </div>
        <div class="gate-item" :class="gate.onBay ? 'is-ok' : 'is-bad'">
          <span>所在机位</span>
          <b>{{ gate.bayNo || '场外' }}</b>
          <em>{{ gate.onBay ? '在位' : '不在机位，阻断' }}</em>
        </div>
        <div class="gate-item" :class="gate.openCardCount === 0 ? 'is-ok' : 'is-bad'">
          <span>未闭环工卡</span>
          <b>{{ gate.openCardCount }} 张</b>
          <em>{{ gate.openCardCount === 0 ? '全部闭环' : '存在未闭环，阻断' }}</em>
        </div>
        <div class="gate-item" :class="gate.overdueDefectCount === 0 ? (gate.openDefectCount ? 'is-warn' : 'is-ok') : 'is-bad'">
          <span>开放 / 超期故障</span>
          <b>{{ gate.openDefectCount }} / {{ gate.overdueDefectCount }}</b>
          <em>{{ gate.overdueDefectCount ? '有超期，阻断' : (gate.openDefectCount ? '开放可保留放行' : '无保留') }}</em>
        </div>
      </div>

      <!-- 阻断原因（服务端给的一致文案） -->
      <el-alert
        v-if="gate && gate.blockers.length"
        class="gate-alert"
        type="error"
        :closable="false"
        title="当前不能进入审签"
      >
        <div v-for="(b, i) in gate.blockers" :key="i">· {{ b }}</div>
      </el-alert>

      <div class="gate-actions">
        <el-button
          type="primary"
          :disabled="!gate || !gate.canInitiate || gate.hasActiveOrder"
          @click="openCreate"
        >
          发起出厂放行（生成证据快照）
        </el-button>
        <el-button
          v-if="gate && gate.hasActiveOrder"
          type="warning"
          plain
          @click="openActive"
        >
          继续在途单 RO#{{ gate.activeOrderId }}（{{ gate.activeOrderStatusName }}）
        </el-button>
        <el-tag v-if="ov.currentAircraftDepartable" type="success" size="large">✔ 已有有效放行，可到机位页执行出位</el-tag>
        <span class="muted">提示：放行按钮只有一个；开放故障须逐条填写保留依据并经质量终审确认。</span>
      </div>

      <!-- 可直接验收的五个剧本（种子数据已就位） -->
      <el-collapse class="scenario">
        <el-collapse-item title="验收剧本（种子数据可直接操作）" name="s">
          <div class="scenario-grid">
            <div class="scenario-item">
              <b>① 正常三人审签 + 放行后才可出位</b>
              <span>切到 B-9001：RO-2026-0001 已终审放行 → 去机位页 BAY-D01 点出位成功；切 B-9003（干净无单）可现场走发起→提交→机械→终审全流程。</span>
              <el-button size="small" @click="pickTail('B-9001')">B-9001 已放行</el-button>
              <el-button size="small" @click="pickTail('B-9003')">B-9003 走全流程</el-button>
            </div>
            <div class="scenario-item">
              <b>② 开放故障带依据放行</b>
              <span>B-9002 的 RO-2026-0002 待质量终审，DF-2026-0014 已逐条填写 MEL 保留依据；终审人须第三人并勾选逐条确认。</span>
              <el-button size="small" @click="pickTail('B-9002')">B-9002 待终审</el-button>
            </div>
            <div class="scenario-item">
              <b>③ 审签中工卡变化 → 已失效</b>
              <span>B-9004 的 RO-2026-0003 因快照后新增工卡 WC-2026-0021 被系统置为已失效；旧机械签字保留在时间线，不可沿用。也可对任意在途单到工卡页改工卡实时观察。</span>
              <el-button size="small" @click="pickTail('B-9004')">B-9004 已失效</el-button>
            </div>
            <div class="scenario-item">
              <b>④ 两人同时终审</b>
              <span>B-9002 在「待质量终审」时点待办区的并发按钮：同人重复点击幂等同结果；两个不同终审人只成一个，另一个被告知当前已放行。</span>
              <el-button size="small" @click="pickTail('B-9002')">去 B-9002 并发终审</el-button>
            </div>
            <div class="scenario-item">
              <b>⑤ 非适航 / 未闭环工卡阻断发起</b>
              <span>B-9005 停场待修且有进行中工卡 WC-2026-0022，发起按钮禁用并列出服务端阻断原因；机位出位同样被一致文案拦截。</span>
              <el-button size="small" @click="pickTail('B-9005')">B-9005 被阻断</el-button>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </section>

    <div class="split">
      <!-- 左：当前飞机放行单列表 -->
      <section class="panel">
        <div class="panel-head">
          <h3>本机放行记录</h3>
          <span class="panel-sub">点单据查看证据快照差异与审计时间线</span>
        </div>
        <el-table :data="orders" size="small" :row-class-name="orderRowClass" @row-click="openDetail" highlight-current-row>
          <el-table-column prop="releaseNo" label="放行单号" width="130" />
          <el-table-column prop="statusName" label="状态" width="104">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ row.statusName }}</el-tag>
              <el-tag v-if="row.stale" type="info" size="small" effect="plain">事实漂移</el-tag>
              <el-tag v-if="row.departed" type="info" size="small" effect="plain">已出位核销</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="三人签字" min-width="200">
            <template #default="{ row }">
              <span>发起 {{ row.initiator }}</span>
              <span v-if="row.mechSigner"> / 机械 {{ row.mechSigner }}</span>
              <span v-if="row.qaSigner"> / 终审 {{ row.qaSigner }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="160" />
        </el-table>
        <el-empty v-if="!orders.length" description="该机暂无放行记录" :image-size="70" />
      </section>

      <!-- 右：最近/选中单据的待办操作 -->
      <section class="panel">
        <div class="panel-head">
          <h3>待办操作</h3>
          <span class="panel-sub">同一终审动作重复点击返回同一结果</span>
        </div>
        <div v-if="detail" class="todo-box">
          <div class="todo-head">
            <el-tag :type="statusTag(detail.order.status)">{{ detail.order.statusName }}</el-tag>
            <b>{{ detail.order.releaseNo }}</b>
            <el-tag v-if="detail.order.stale" type="info" effect="plain">放行后事实漂移·不可出位</el-tag>
            <el-tag v-else-if="detail.order.departed" type="info" effect="plain">已出位核销·不可重复出位</el-tag>
          </div>

          <el-alert
            v-if="detail.order.rejectReason"
            class="todo-alert"
            type="warning"
            :closable="false"
            :title="'驳回原因：' + detail.order.rejectReason"
          />

          <!-- 操作人（任一环节都必须选本人） -->
          <div class="todo-signer" v-if="detail.actions.length">
            <span>当前操作人</span>
            <el-select v-model="actor" placeholder="选择本人姓名" style="width: 200px">
              <el-option v-for="p in STAFF" :key="p" :label="p" :value="p" />
            </el-select>
          </div>

          <div class="todo-btns">
            <el-button v-if="detail.actions.includes('SUBMIT')" type="primary" @click="openSubmit">
              {{ detail.order.status === 'REJECTED' ? '修订后再次提交（生成新快照）' : '提交审签' }}
            </el-button>
            <el-button v-if="detail.actions.includes('MECH_SIGN')" type="primary" @click="doMechSign">
              机械放行签字
            </el-button>
            <el-button v-if="detail.actions.includes('QA_APPROVE')" type="success" @click="openQa">
              质量终审通过
            </el-button>
            <el-button v-if="detail.actions.includes('REJECT')" type="danger" plain @click="openReject">
              驳回
            </el-button>
            <el-button v-if="detail.actions.includes('DEPART')" type="warning" plain @click="goDepart">
              到机位页执行出位
            </el-button>
          </div>

          <!-- 待质量终审：两人同时终审验收演示 -->
          <div class="demo-box" v-if="detail.actions.includes('QA_APPROVE')">
            <div class="demo-title">并发一致性验收（待质量终审）</div>
            <el-button size="small" @click="runConcurrent(true)">模拟同一人重复点击终审（应幂等同结果）</el-button>
            <el-button size="small" type="danger" plain @click="runConcurrent(false)">
              模拟两个不同终审人同时终审（只成一个）
            </el-button>
          </div>

          <div v-if="!detail.actions.length" class="muted">
            {{ detail.order.status === 'INVALID'
              ? '该单已失效：上游事实在审签期间变化，已有签字保留作审计，不可沿用，请重新发起。'
              : '当前状态无可执行操作。' }}
          </div>
        </div>
        <el-empty v-else description="选择左侧单据查看待办" :image-size="80" />
      </section>
    </div>

    <!-- 单据详情：证据快照 + 差异 + 时间线 -->
    <section class="panel" v-if="detail">
      <div class="panel-head">
        <h3>{{ detail.order.releaseNo }} · 证据快照与审计</h3>
        <span class="panel-sub">快照只追加、不可变；差异为快照与当前服务端事实实时对比</span>
        <div class="panel-extra">
          <el-select v-model="viewRound" size="small" style="width: 150px" @change="loadDiff">
            <el-option
              v-for="s in detail.snapshots"
              :key="s.roundNo"
              :label="'第 ' + s.roundNo + ' 轮快照'"
              :value="s.roundNo"
            />
          </el-select>
        </div>
      </div>

      <div class="snap-summary" v-if="latestSnap">
        <div><span>取证时间</span><b>{{ latestSnap.takenAt }}</b></div>
        <div><span>机号 / 机型</span><b>{{ latestSnap.tailNo }} · {{ latestSnap.model }}</b></div>
        <div><span>适航状态</span><b>{{ latestSnap.airworthyName }}</b></div>
        <div><span>所在机位</span><b>{{ latestSnap.bayNo || '场外' }}</b></div>
        <div><span>未闭环工卡</span><b>{{ latestSnap.openCardCount }}</b></div>
        <div><span>开放 / 超期故障</span><b>{{ latestSnap.openDefectCount }} / {{ latestSnap.overdueDefectCount }}</b></div>
      </div>

      <!-- 快照差异 -->
      <el-alert
        class="diff-alert"
        :type="diff.blocking ? 'error' : (diff.changed ? 'warning' : 'success')"
        :closable="false"
        :title="diff.summary"
        show-icon
      />
      <el-table v-if="diff.items && diff.items.length" :data="diff.items" size="small" class="diff-table">
        <el-table-column prop="kind" label="类别" width="90" />
        <el-table-column prop="refNo" label="编号" width="130" />
        <el-table-column prop="title" label="变化项" min-width="220" />
        <el-table-column prop="snapshotText" label="快照当时" width="120" />
        <el-table-column prop="currentText" label="当前事实" width="120" />
        <el-table-column label="影响" width="90">
          <template #default="{ row }">
            <el-tag :type="row.blocking ? 'danger' : 'info'" size="small">
              {{ row.blocking ? '阻断' : '提示' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <!-- 快照明细：未闭环工卡 -->
      <template v-if="latestSnap">
        <div class="snap-sub">快照内未闭环工卡（编号 / 状态 / 更新时间）</div>
        <el-table :data="latestSnap.cards" size="small">
          <el-table-column prop="cardNo" label="工卡号" width="130" />
          <el-table-column prop="title" label="内容" min-width="200" />
          <el-table-column prop="statusName" label="状态" width="90" />
          <el-table-column prop="executor" label="执行人" width="90" />
          <el-table-column prop="cardUpdatedAt" label="工卡更新时间" width="160" />
        </el-table>
        <el-empty v-if="!latestSnap.cards.length" description="快照时无未闭环工卡" :image-size="50" />

        <!-- 快照明细：开放/超期故障 + 保留依据 + 终审确认 -->
        <div class="snap-sub">快照内开放 / 超期故障（保留依据逐条留痕，终审逐条确认）</div>
        <el-table :data="latestSnap.defects" size="small">
          <el-table-column prop="defectNo" label="缺陷单号" width="130" />
          <el-table-column prop="description" label="故障" min-width="220" />
          <el-table-column prop="statusName" label="状态" width="80" />
          <el-table-column prop="dueDate" label="到期日" width="110" />
          <el-table-column prop="retainBasis" label="保留依据" min-width="240" show-overflow-tooltip />
          <el-table-column label="终审确认" width="90">
            <template #default="{ row }">
              <el-tag :type="row.qaConfirmed ? 'success' : 'info'" size="small">
                {{ row.qaConfirmed ? '已确认' : '待确认' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!latestSnap.defects.length" description="快照时无开放 / 超期故障" :image-size="50" />
      </template>

      <!-- 完整审计时间线（服务端事实，含签字人与时间） -->
      <div class="snap-sub">完整审计时间线</div>
      <el-timeline class="audit-timeline">
        <el-timeline-item
          v-for="e in detail.events"
          :key="e.id"
          :timestamp="(e.actor ? e.actor + ' · ' : '') + e.eventAt"
          placement="top"
          :color="eventColor(e.eventType)"
        >
          <b>{{ e.eventTypeName }}</b>
          <div class="event-detail">{{ e.detail }}</div>
        </el-timeline-item>
      </el-timeline>
    </section>

    <!-- 发起弹窗 -->
    <el-dialog v-model="createVisible" title="发起出厂放行审签" width="460px">
      <el-form label-width="90px">
        <el-form-item label="飞机">
          <b>{{ current ? current.tailNo + ' · ' + current.model : '' }}</b>
        </el-form-item>
        <el-form-item label="发起人">
          <el-select v-model="createInitiator" placeholder="选择发起人（机械员）" style="width: 240px">
            <el-option v-for="p in STAFF" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="muted">发起即对当前适航 / 机位 / 工卡 / 故障取证，生成第 1 轮不可变证据快照。</div>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!createInitiator" @click="doCreate">确认发起</el-button>
      </template>
    </el-dialog>

    <!-- 提交弹窗：开放故障逐条保留依据 -->
    <el-dialog v-model="submitVisible" title="提交审签（形成新一轮证据快照）" width="640px">
      <el-form label-width="90px">
        <el-form-item label="提交人">
          <el-input :model-value="detail ? detail.order.initiator : ''" disabled style="width: 240px" />
          <span class="muted">必须发起人本人提交</span>
        </el-form-item>
        <el-form-item
          v-for="d in submitDefects"
          :key="d.defectId"
          :label="d.defectNo"
          required
        >
          <div class="retain-item">
            <div class="muted">{{ d.description }}（到期 {{ d.dueDate }}）</div>
            <el-input
              v-model="retainBasis[d.defectId]"
              type="textarea"
              :rows="2"
              placeholder="逐条写明保留依据：MEL 条款 / 工程批准文号 / 后续计划"
            />
          </div>
        </el-form-item>
        <div v-if="!submitDefects.length" class="muted">当前无开放故障，无需保留依据。</div>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="doSubmit">生成新快照并提交</el-button>
      </template>
    </el-dialog>

    <!-- 终审弹窗：逐条勾选确认保留依据 -->
    <el-dialog v-model="qaVisible" title="质量终审（必须逐条确认开放故障保留依据）" width="640px">
      <el-checkbox-group v-model="qaConfirmed">
        <el-checkbox
          v-for="d in qaDefects"
          :key="d.defectId"
          :value="d.defectId"
          class="qa-check"
          :label="d.defectId"
        >
          <b>{{ d.defectNo }}</b> {{ d.description }}
          <div class="retain-basis-text">保留依据：{{ d.retainBasis }}</div>
        </el-checkbox>
      </el-checkbox-group>
      <el-alert
        v-if="!qaDefects.length"
        type="success"
        :closable="false"
        title="无开放故障，确认适航/工卡/机位快照无误即可终审"
      />
      <div class="qa-signer muted">终审人不能与发起人、机械放行人相同。</div>
      <template #footer>
        <el-button @click="qaVisible = false">取消</el-button>
        <el-button
          type="success"
          :disabled="!actor || qaConfirmed.length !== qaDefects.length"
          @click="doQaApprove"
        >
          终审通过并放行
        </el-button>
      </template>
    </el-dialog>

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectVisible" title="驳回（回到可修订状态）" width="480px">
      <el-alert type="warning" :closable="false" title="驳回必须留下原因，发起人修订后再次提交会生成新快照" />
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="请填写驳回原因" style="margin-top: 10px" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :disabled="!actor || !rejectReason.trim()" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 并发演示结果 -->
    <el-dialog v-model="concurrentVisible" title="两人同时终审 · 结果" width="640px">
      <el-alert :closable="false" type="success" :title="concurrentMessage" />
      <el-table :data="concurrentResults" size="small" style="margin-top: 10px">
        <el-table-column prop="actor" label="终审人" width="100" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? '成功' : '未成功' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="返回信息" min-width="260" />
        <el-table-column prop="finalStatusName" label="单据最终状态" width="110" />
      </el-table>
      <template #footer>
        <el-button type="primary" @click="concurrentVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { releaseApi } from '../api'

/** 演示用花名册：发起人 / 机械放行人 / 质量终审人必须是其中三个不同的人 */
const STAFF = ['陈志强', '孙立伟', '王大鹏', '钱明亮', '罗建军', '周国平', '刘振华']

const mro = inject('mro')
const currentId = mro.aircraftId
const current = computed(() => mro.aircraftList.value.find((a) => a.id === currentId.value) || null)
const router = useRouter()

const ov = mro.releaseOverview
const gate = ref(null)
const orders = ref([])
const detail = ref(null)
const actor = ref('')
const viewRound = ref(null)
const diff = ref(emptyDiff())

const createVisible = ref(false)
const createInitiator = ref('')
const submitVisible = ref(false)
const qaVisible = ref(false)
const rejectVisible = ref(false)
const rejectReason = ref('')
const retainBasis = ref({})
const qaConfirmed = ref([])
const concurrentVisible = ref(false)
const concurrentResults = ref([])
const concurrentMessage = ref('')

function emptyDiff() {
  return { items: [], changed: false, blocking: false, summary: '加载中…' }
}

const latestSnap = computed(() => (detail.value ? detail.value.latestSnapshot : null))
const submitDefects = computed(() => (latestSnap.value ? latestSnap.value.defects.filter((d) => d.status === 'OPEN') : []))
const qaDefects = computed(() => submitDefects.value)

function statusTag(status) {
  return {
    DRAFT: 'info',
    PENDING_MECH: 'warning',
    PENDING_QA: 'warning',
    RELEASED: 'success',
    REJECTED: 'danger',
    INVALID: 'danger'
  }[status] || 'info'
}

function orderRowClass({ row }) {
  return row.status === 'INVALID' || row.stale ? 'row-invalid' : ''
}

function eventColor(type) {
  return {
    CREATED: '#90a4ae',
    SUBMITTED: '#1565c0',
    MECH_SIGNED: '#f9a825',
    QA_APPROVED: '#2e7d32',
    REJECTED: '#ef6c00',
    INVALIDATED: '#c62828',
    FACT_DRIFTED: '#c62828',
    DEPARTED: '#00838f'
  }[type] || '#90a4ae'
}

async function loadAll() {
  if (!currentId.value) {
    gate.value = null
    orders.value = []
    detail.value = null
    return
  }
  const [g, os, o] = await Promise.all([
    releaseApi.gate(currentId.value),
    releaseApi.list(currentId.value),
    mro.refreshOverview()
  ])
  gate.value = g
  orders.value = os
  // 进入模块：优先打开在途单，否则最近一条
  const keep = detail.value && os.some((x) => x.id === detail.value.order.id) ? detail.value.order.id : null
  const target = keep || (g.hasActiveOrder ? g.activeOrderId : (os[0] ? os[0].id : null))
  if (target) {
    await openDetailById(target)
  } else {
    detail.value = null
  }
}

async function openDetail(row) {
  await openDetailById(row.id)
}

async function openDetailById(id) {
  detail.value = await releaseApi.detail(id)
  viewRound.value = detail.value.latestSnapshot ? detail.value.latestSnapshot.roundNo : null
  diff.value = detail.value.diff || emptyDiff()
}

async function loadDiff() {
  if (!detail.value) return
  diff.value = await releaseApi.diff(detail.value.order.id, viewRound.value)
}

function openActive() {
  if (gate.value && gate.value.activeOrderId) openDetailById(gate.value.activeOrderId)
}

function openCreate() {
  createInitiator.value = ''
  createVisible.value = true
}

async function doCreate() {
  const d = await releaseApi.create({ aircraftId: currentId.value, initiator: createInitiator.value })
  ElMessage.success('已发起，第 1 轮证据快照已生成')
  createVisible.value = false
  await loadAll()
  await openDetailById(d.order.id)
}

function openSubmit() {
  retainBasis.value = {}
  for (const d of submitDefects.value) retainBasis.value[d.defectId] = d.retainBasis || ''
  submitVisible.value = true
}

async function doSubmit() {
  const list = Object.entries(retainBasis.value)
    .filter(([, v]) => v && v.trim())
    .map(([defectId, retainBasis]) => ({ defectId: Number(defectId), retainBasis }))
  const missing = submitDefects.value.filter((d) => !retainBasis.value[d.defectId] || !retainBasis.value[d.defectId].trim())
  if (missing.length) {
    ElMessage.error('开放故障 ' + missing.map((d) => d.defectNo).join('、') + ' 必须逐条写明保留依据')
    return
  }
  await releaseApi.submit(detail.value.order.id, {
    submitter: detail.value.order.initiator,
    retainBasisList: list
  })
  ElMessage.success('已生成新一轮快照并提交，进入待机械放行')
  submitVisible.value = false
  await loadAll()
}

async function doMechSign() {
  if (!actor.value) return ElMessage.warning('请先选择当前操作人')
  await releaseApi.mechSign(detail.value.order.id, actor.value)
  ElMessage.success('机械放行已签字')
  await loadAll()
}

function openQa() {
  if (!actor.value) return ElMessage.warning('请先选择当前操作人（质量终审人）')
  const o = detail.value.order
  if (actor.value === o.initiator || actor.value === o.mechSigner) {
    return ElMessage.warning('质量终审人必须是不同于发起人、机械放行人的第三人')
  }
  qaConfirmed.value = qaDefects.value.filter((d) => d.qaConfirmed).map((d) => d.defectId)
  qaVisible.value = true
}

async function doQaApprove() {
  await releaseApi.qaApprove(detail.value.order.id, {
    signer: actor.value,
    confirmedDefectIds: qaConfirmed.value
  })
  ElMessage.success('质量终审通过，飞机准许出厂出位')
  qaVisible.value = false
  await loadAll()
}

function openReject() {
  if (!actor.value) return ElMessage.warning('请先选择当前操作人')
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doReject() {
  await releaseApi.reject(detail.value.order.id, { signer: actor.value, reason: rejectReason.value.trim() })
  ElMessage.success('已驳回，发起人可修订后重新提交')
  rejectVisible.value = false
  await loadAll()
}

async function runConcurrent(sameActor) {
  // 固定使用与发起人、机械放行人不同的第三人，保证演示结果确定
  const signer = '罗建军'
  const ids = qaDefects.value.map((d) => d.defectId)
  try {
    const res = await releaseApi.concurrentQa(detail.value.order.id, sameActor, { signer, confirmedDefectIds: ids })
    concurrentResults.value = res
    concurrentMessage.value = res.message || '并发完成'
    concurrentVisible.value = true
  } finally {
    await loadAll()
  }
}

function goDepart() {
  ElMessageBox.alert('出厂放行已通过，请到「机库机位平面」点击该机所在机位，使用唯一的「安排出位（出厂）」入口。系统会再次用服务端快照核验放行是否仍有效。', '前往出位', {
    confirmButtonText: '去机位页'
  }).then(() => router.push('/hangar')).catch(() => {})
}

/** 验收剧本：切顶栏机号（全局上下文随之刷新本页） */
function pickTail(tailNo) {
  const hit = mro.aircraftList.value.find((a) => a.tailNo === tailNo)
  if (!hit) {
    ElMessage.warning('未找到飞机 ' + tailNo)
    return
  }
  currentId.value = hit.id
  localStorage.setItem('mro-tail-id', String(hit.id))
}

watch(currentId, loadAll)

onMounted(loadAll)
</script>

<style scoped>
.release-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.rel-overview {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.rel-stat {
  flex: 1;
  min-width: 110px;
  background: #f4f8fd;
  border: 1px solid var(--mro-border);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.rel-stat b {
  font-size: 22px;
  color: var(--mro-primary-dark);
}
.rel-stat span {
  font-size: 12px;
  color: var(--mro-text-sub);
}
.rel-stat.is-warn b { color: var(--mro-warn); }
.rel-stat.is-danger b { color: var(--mro-danger); }
.rel-stat.is-ok b { color: var(--mro-ok); }

.gate-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin: 8px 0;
}
.gate-item {
  border-radius: 8px;
  padding: 10px 12px;
  border: 1px solid var(--mro-border);
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.gate-item span { font-size: 12px; color: var(--mro-text-sub); }
.gate-item b { font-size: 15px; }
.gate-item em { font-style: normal; font-size: 12px; }
.gate-item.is-ok { background: #eef7ee; border-color: #a5d6a7; }
.gate-item.is-ok em { color: var(--mro-ok); }
.gate-item.is-warn { background: #fff5e8; border-color: #ffcc80; }
.gate-item.is-warn em { color: var(--mro-warn); }
.gate-item.is-bad { background: #fdecea; border-color: #ef9a9a; }
.gate-item.is-bad em { color: var(--mro-danger); }

.gate-alert { margin: 6px 0; }
.gate-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.scenario {
  margin-top: 12px;
  border-top: 1px dashed var(--mro-border);
}
.scenario-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}
.scenario-item {
  border: 1px solid var(--mro-border);
  border-radius: 8px;
  padding: 10px;
  background: #fafcff;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.scenario-item b {
  color: var(--mro-primary-dark);
}
.scenario-item span {
  font-size: 12px;
  color: var(--mro-text-sub);
  line-height: 1.5;
}
.scenario-item .el-button {
  align-self: flex-start;
  margin-right: 6px;
}

.split {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 12px;
}
.todo-box { display: flex; flex-direction: column; gap: 10px; }
.todo-head { display: flex; align-items: center; gap: 8px; }
.todo-alert { margin: 0; }
.todo-signer { display: flex; align-items: center; gap: 10px; }
.todo-btns { display: flex; gap: 8px; flex-wrap: wrap; }
.demo-box {
  border: 1px dashed var(--mro-border);
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fafcff;
}
.demo-title { font-weight: 700; font-size: 13px; }
.demo-box .el-button { margin-right: 8px; }

:deep(.row-invalid) {
  color: var(--mro-danger);
  background: #fdf3f2 !important;
}

.snap-summary {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  margin: 10px 0;
}
.snap-summary > div {
  background: #f4f8fd;
  border-radius: 6px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.snap-summary span { font-size: 11px; color: var(--mro-text-sub); }
.diff-alert { margin: 8px 0; }
.diff-table { margin-bottom: 12px; }
.snap-sub {
  font-weight: 700;
  margin: 14px 0 6px;
  padding-left: 8px;
  border-left: 3px solid var(--mro-primary);
}
.audit-timeline { padding: 8px 0 0 8px; }
.event-detail { color: var(--mro-text-sub); font-size: 13px; }
.retain-item { width: 100%; }
.retain-basis-text {
  font-size: 12px;
  color: var(--mro-text-sub);
  font-weight: 400;
  padding-left: 20px;
}
.qa-check {
  display: flex;
  width: 100%;
  height: auto;
  margin-bottom: 8px;
  align-items: flex-start;
  white-space: normal;
}
.qa-signer { margin-top: 10px; }
</style>
