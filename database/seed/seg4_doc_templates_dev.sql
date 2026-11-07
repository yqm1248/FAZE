-- ============================================================================
-- 第 4 段开发/验收用：把 assets/doc-templates 下的 5 份模板灌进 doc_template（faze 库）
--
-- ⚠️ **这不是生产种子数据**：工作流 §三 A1 明确「5 份模板是运营在 A-18 上传的运行时数据」。
--    本脚本只用于开发库与端到端验收；生产环境由运营在后台新建模板（POST /admin/doc-templates）。
-- 幂等：按 (code, lang, version) 唯一键 ON DUPLICATE KEY UPDATE，重复执行不产生重复行。
-- 执行：mysql --host=127.0.0.1 --user=faze_migrate --password=*** faze
-- ============================================================================

INSERT INTO doc_template (code, name, category, template_html, field_schema, lang, version, sort_order, status, created_at, updated_at)
VALUES ('LAWSUIT-001', '民事起诉状', '诉讼文书', '<html>
<head>
<meta charset="utf-8" />
<style>
@page { size: A4; margin: 72pt 72pt 72pt 90pt; }
body { font-family: ''FangSong''; font-size: 16pt; line-height: 28pt; margin: 0; color: #000000; }
h1 { font-size: 22pt; text-align: center; font-weight: bold; margin: 0 0 24pt 0; line-height: 32pt; }
p { margin: 0 0 6pt 0; text-align: justify; }
div.section { margin-top: 12pt; margin-bottom: 6pt; font-weight: bold; }
ol { margin: 0 0 6pt 0; padding-left: 24pt; }
li { margin-bottom: 4pt; text-align: justify; }
div.signature { margin-top: 36pt; text-align: right; line-height: 28pt; }
</style>
</head>
<body>
<h1>民事起诉状</h1>

<p>原告：{{#plaintiffs}}{{item.name}}，{{item.gender}}，{{item.birth}}出生，{{item.ethnicity}}族，住{{item.address}}，联系电话 {{item.phone}}。{{/plaintiffs}}</p>

<p>被告：{{#defendants}}{{item.name}}，住所地{{item.address}}，联系电话 {{item.phone}}。{{/defendants}}</p>

<div class="section">诉讼请求</div>
<ol>{{#claims}}<li>{{item.text}}</li>{{/claims}}</ol>

<div class="section">事实与理由</div>
<p>{{facts}}</p>

<p>综上所述，原告为维护自身合法权益，依据《中华人民共和国民事诉讼法》的相关规定，向贵院提起诉讼，恳请依法裁判。</p>

<div class="signature">
<p>此致</p>
<p>{{court_name}}</p>
<p>具状人：{{#plaintiffs}}{{item.name}}{{/plaintiffs}}</p>
<p>{{filing_date}}</p>
</div>
</body>
</html>
', '{
  "version": 1,
  "fields": [
    {
      "key": "court_name",
      "label": "受理法院",
      "type": "text",
      "required": true,
      "maxLength": 50,
      "placeholder": "如：伊宁市人民法院"
    },
    {
      "key": "plaintiffs",
      "label": "原告信息",
      "type": "array",
      "required": true,
      "minItems": 1,
      "maxItems": 5,
      "itemSchema": {
        "fields": [
          { "key": "name", "label": "姓名", "type": "text", "required": true, "maxLength": 30 },
          { "key": "gender", "label": "性别", "type": "text", "required": true, "maxLength": 4 },
          { "key": "birth", "label": "出生日期", "type": "date", "required": true },
          { "key": "ethnicity", "label": "民族", "type": "text", "maxLength": 20 },
          { "key": "address", "label": "住址", "type": "text", "required": true, "maxLength": 200 },
          { "key": "phone", "label": "联系电话", "type": "text", "required": true, "maxLength": 20 }
        ]
      }
    },
    {
      "key": "defendants",
      "label": "被告信息",
      "type": "array",
      "required": true,
      "minItems": 1,
      "maxItems": 5,
      "itemSchema": {
        "fields": [
          { "key": "name", "label": "名称/姓名", "type": "text", "required": true, "maxLength": 100 },
          { "key": "address", "label": "住所地", "type": "text", "required": true, "maxLength": 200 },
          { "key": "phone", "label": "联系电话", "type": "text", "maxLength": 20 }
        ]
      }
    },
    {
      "key": "claims",
      "label": "诉讼请求",
      "type": "array",
      "required": true,
      "minItems": 1,
      "maxItems": 10,
      "itemSchema": {
        "fields": [
          { "key": "text", "label": "请求内容", "type": "textarea", "required": true, "maxLength": 500 }
        ]
      }
    },
    {
      "key": "facts",
      "label": "事实与理由",
      "type": "textarea",
      "required": true,
      "maxLength": 5000
    },
    {
      "key": "filing_date",
      "label": "具状日期",
      "type": "date",
      "required": true
    }
  ]
}', 'zh-Hans', 1, 1, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category), template_html = VALUES(template_html), field_schema = VALUES(field_schema), sort_order = VALUES(sort_order), status = VALUES(status), updated_at = VALUES(updated_at);

INSERT INTO doc_template (code, name, category, template_html, field_schema, lang, version, sort_order, status, created_at, updated_at)
VALUES ('EVIDENCE-001', '证据清单', '诉讼文书', '<html>
<head>
<meta charset="utf-8" />
<style>
@page { size: A4; margin: 72pt 72pt 72pt 90pt; }
body { font-family: ''FangSong''; font-size: 16pt; line-height: 28pt; margin: 0; color: #000000; }
h1 { font-size: 22pt; text-align: center; font-weight: bold; margin: 0 0 24pt 0; line-height: 32pt; }
p { margin: 0 0 6pt 0; text-align: justify; }
table { width: 100%; border-collapse: collapse; margin-top: 8pt; }
th, td { border: 1pt solid #000000; padding: 4pt 6pt; font-size: 14pt; line-height: 22pt; text-align: left; vertical-align: top; }
th { font-weight: bold; text-align: center; }
div.signature { margin-top: 36pt; text-align: right; line-height: 28pt; }
</style>
</head>
<body>
<h1>证据清单</h1>

<p>案由：{{case_cause}}</p>
<p>提交人：{{submitter}}</p>

<table>
<thead>
<tr><th>证据名称</th><th>证据来源</th><th>证明目的</th></tr>
</thead>
<tbody>
{{#evidence_items}}<tr><td>{{item.name}}</td><td>{{item.source}}</td><td>{{item.purpose}}</td></tr>{{/evidence_items}}
</tbody>
</table>

<p>以上证据均为原件或与原件核对无异的复印件，随本清单一并提交。</p>

<div class="signature">
<p>提交人：{{submitter}}</p>
<p>{{submit_date}}</p>
</div>
</body>
</html>
', '{
  "version": 1,
  "fields": [
    { "key": "case_cause", "label": "案由", "type": "text", "required": true, "maxLength": 100 },
    { "key": "submitter", "label": "提交人", "type": "text", "required": true, "maxLength": 50 },
    {
      "key": "evidence_items",
      "label": "证据条目",
      "type": "array",
      "required": true,
      "minItems": 1,
      "maxItems": 30,
      "itemSchema": {
        "fields": [
          { "key": "name", "label": "证据名称", "type": "text", "required": true, "maxLength": 100 },
          { "key": "source", "label": "证据来源", "type": "text", "required": true, "maxLength": 100 },
          { "key": "purpose", "label": "证明目的", "type": "textarea", "required": true, "maxLength": 300 }
        ]
      }
    },
    { "key": "submit_date", "label": "提交日期", "type": "date", "required": true }
  ]
}', 'zh-Hans', 1, 2, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category), template_html = VALUES(template_html), field_schema = VALUES(field_schema), sort_order = VALUES(sort_order), status = VALUES(status), updated_at = VALUES(updated_at);

INSERT INTO doc_template (code, name, category, template_html, field_schema, lang, version, sort_order, status, created_at, updated_at)
VALUES ('LABOR-001', '劳动合同', '劳动文书', '<html>
<head>
<meta charset="utf-8" />
<!-- 内容基准：人力资源和社会保障部《劳动合同（示范文本）》。
     固定条款按示范文本结构写死，不允许用户改；可填字段用 {{}} 占位。
     本文件尚未与官方原文逐字核对，交付前需人工核对一次（见《第 4 段工作流》§六 R3）。 -->
<style>
@page { size: A4; margin: 72pt 72pt 72pt 90pt; }
body { font-family: ''FangSong''; font-size: 16pt; line-height: 28pt; margin: 0; color: #000000; }
h1 { font-size: 22pt; text-align: center; font-weight: bold; margin: 0 0 24pt 0; line-height: 32pt; }
p { margin: 0 0 6pt 0; text-align: justify; }
div.clause { margin-top: 10pt; margin-bottom: 4pt; font-weight: bold; }
div.signature { margin-top: 36pt; line-height: 28pt; }
</style>
</head>
<body>
<h1>劳动合同</h1>

<p>甲方（用人单位）：{{employer_name}}</p>
<p>法定代表人：{{employer_legal_rep}}</p>
<p>住所地：{{employer_address}}</p>
<p>乙方（劳动者）：{{employee_name}}</p>
<p>公民身份号码：{{employee_id_card}}</p>
<p>住址：{{employee_address}}</p>

<p>根据《中华人民共和国劳动法》《中华人民共和国劳动合同法》及有关法律法规，甲乙双方经平等协商，自愿签订本合同，共同遵守本合同所列条款。</p>

<div class="clause">第一条　合同期限</div>
<p>本合同为固定期限劳动合同，自 {{contract_start}} 起至 {{contract_end}} 止。</p>

<div class="clause">第二条　工作内容和工作地点</div>
<p>乙方同意根据甲方工作需要，担任 {{position}} 岗位工作，工作地点为 {{work_location}}。乙方应按照甲方的要求，按时完成规定的工作数量，达到规定的质量标准。</p>

<div class="clause">第三条　工作时间和休息休假</div>
<p>甲方安排乙方执行标准工时制度，即每日工作不超过八小时、每周工作不超过四十小时。甲方应保证乙方每周至少休息一日，并依法安排乙方享受法定节假日与带薪年休假。</p>

<div class="clause">第四条　劳动报酬</div>
<p>甲方每月以货币形式支付乙方工资，月工资为 {{salary}}。甲方不得克扣或者无故拖欠乙方工资。工资支付周期与支付日按甲方依法制定的规章制度执行，但不得违反法律法规的强制性规定。</p>

<div class="clause">第五条　社会保险</div>
<p>甲乙双方应依法参加社会保险，甲方应按国家和地方有关规定为乙方办理社会保险登记并按时足额缴纳社会保险费，乙方个人应缴纳部分由甲方代扣代缴。</p>

<div class="clause">第六条　劳动保护、劳动条件和职业危害防护</div>
<p>甲方应为乙方提供符合国家规定的劳动安全卫生条件和必要的劳动防护用品，对乙方进行劳动安全卫生教育，防止劳动过程中的事故，减少职业危害。</p>

<div class="clause">第七条　劳动合同的变更、解除、终止</div>
<p>甲乙双方变更、解除、终止本合同，应当依照《中华人民共和国劳动合同法》及有关法律法规的规定执行。本合同期满前，双方可协商续订。</p>

<div class="clause">第八条　其他</div>
<p>本合同未尽事宜，按国家和地方有关法律法规执行。本合同一式两份，甲乙双方各执一份，自双方签字（盖章）之日起生效。</p>

<div class="signature">
<p>甲方（盖章）：{{employer_name}}　　　　乙方（签字）：{{employee_name}}</p>
<p>法定代表人（签字）：{{employer_legal_rep}}</p>
<p>签订日期：{{sign_date}}</p>
</div>
</body>
</html>
', '{
  "version": 1,
  "fields": [
    { "key": "employer_name", "label": "甲方名称", "type": "text", "required": true, "maxLength": 100 },
    { "key": "employer_legal_rep", "label": "法定代表人", "type": "text", "required": true, "maxLength": 30 },
    { "key": "employer_address", "label": "甲方住所地", "type": "text", "required": true, "maxLength": 200 },
    { "key": "employee_name", "label": "乙方姓名", "type": "text", "required": true, "maxLength": 30 },
    { "key": "employee_id_card", "label": "乙方身份证号", "type": "text", "required": true, "maxLength": 18 },
    { "key": "employee_address", "label": "乙方住址", "type": "text", "required": true, "maxLength": 200 },
    { "key": "position", "label": "岗位", "type": "text", "required": true, "maxLength": 50 },
    { "key": "work_location", "label": "工作地点", "type": "text", "required": true, "maxLength": 100 },
    { "key": "contract_start", "label": "合同起始日", "type": "date", "required": true },
    { "key": "contract_end", "label": "合同终止日", "type": "date", "required": true },
    { "key": "salary", "label": "月工资（含币种）", "type": "text", "required": true, "maxLength": 50 },
    { "key": "sign_date", "label": "签订日期", "type": "date", "required": true }
  ]
}', 'zh-Hans', 1, 3, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category), template_html = VALUES(template_html), field_schema = VALUES(field_schema), sort_order = VALUES(sort_order), status = VALUES(status), updated_at = VALUES(updated_at);

INSERT INTO doc_template (code, name, category, template_html, field_schema, lang, version, sort_order, status, created_at, updated_at)
VALUES ('CROSSBORDER-001', '跨境电商合同', '跨境电商', '<html>
<head>
<meta charset="utf-8" />
<!-- 本模板没有官方统一示范文本，由法泽参照跨境电子商务交易惯例拟定。
     使用时必须在正文标明「非官方文本」，交付前需人工核对一次（见《第 4 段工作流》§六 R3）。 -->
<style>
@page { size: A4; margin: 72pt 72pt 72pt 90pt; }
body { font-family: ''FangSong''; font-size: 16pt; line-height: 28pt; margin: 0; color: #000000; }
h1 { font-size: 22pt; text-align: center; font-weight: bold; margin: 0 0 24pt 0; line-height: 32pt; }
p { margin: 0 0 6pt 0; text-align: justify; }
div.clause { margin-top: 10pt; margin-bottom: 4pt; font-weight: bold; }
div.notice { margin: 0 0 12pt 0; font-size: 12pt; line-height: 20pt; text-align: center; }
table { width: 100%; border-collapse: collapse; margin-top: 8pt; }
th, td { border: 1pt solid #000000; padding: 4pt 6pt; font-size: 14pt; line-height: 22pt; text-align: left; vertical-align: top; }
th { font-weight: bold; text-align: center; }
div.signature { margin-top: 36pt; line-height: 28pt; }
</style>
</head>
<body>
<h1>跨境电商买卖合同</h1>
<div class="notice">（非官方文本，由法泽拟定，仅供参考）</div>

<p>卖方：{{seller_name}}</p>
<p>卖方地址：{{seller_address}}</p>
<p>买方：{{buyer_name}}</p>
<p>买方地址：{{buyer_address}}</p>

<p>经买卖双方友好协商，就下列商品的跨境买卖事宜达成如下条款，共同遵守。</p>

<div class="clause">第一条　商品与价款</div>
<table>
<thead>
<tr><th>商品名称</th><th>规格</th><th>数量</th><th>单价</th></tr>
</thead>
<tbody>
{{#goods}}<tr><td>{{item.name}}</td><td>{{item.spec}}</td><td>{{item.quantity}}</td><td>{{item.unit_price}}</td></tr>{{/goods}}
</tbody>
</table>

<div class="clause">第二条　交货与运输</div>
<p>{{delivery_terms}}</p>

<div class="clause">第三条　付款方式</div>
<p>买方应按双方约定的方式与期限支付价款。跨境支付产生的手续费、汇兑损失及税费，除另有约定外，由各自承担依法应承担的部分。</p>

<div class="clause">第四条　检验与验收</div>
<p>买方应在收到商品后的合理期限内进行检验。商品存在质量不符、数量短少或运输损坏的，买方应及时书面通知卖方并提供相应凭证；逾期未通知的，视为验收合格。</p>

<div class="clause">第五条　违约责任</div>
<p>任何一方不履行或不完全履行本合同约定义务的，应承担相应的违约责任并赔偿对方因此遭受的损失。因不可抗力导致不能履行的，根据不可抗力的影响部分或全部免除责任。</p>

<div class="clause">第六条　争议解决</div>
<p>{{dispute_resolution}}</p>

<div class="clause">第七条　其他</div>
<p>本合同自双方签字（盖章）之日起生效。本合同未尽事宜，双方可另行协商签订补充协议，补充协议与本合同具有同等效力。</p>

<div class="signature">
<p>卖方（签字/盖章）：{{seller_name}}　　　　买方（签字/盖章）：{{buyer_name}}</p>
<p>签订日期：{{sign_date}}</p>
</div>
</body>
</html>
', '{
  "version": 1,
  "fields": [
    { "key": "seller_name", "label": "卖方名称", "type": "text", "required": true, "maxLength": 100 },
    { "key": "seller_address", "label": "卖方地址", "type": "text", "required": true, "maxLength": 200 },
    { "key": "buyer_name", "label": "买方名称", "type": "text", "required": true, "maxLength": 100 },
    { "key": "buyer_address", "label": "买方地址", "type": "text", "required": true, "maxLength": 200 },
    {
      "key": "goods",
      "label": "商品清单",
      "type": "array",
      "required": true,
      "minItems": 1,
      "maxItems": 20,
      "itemSchema": {
        "fields": [
          { "key": "name", "label": "商品名称", "type": "text", "required": true, "maxLength": 100 },
          { "key": "spec", "label": "规格", "type": "text", "maxLength": 100 },
          { "key": "quantity", "label": "数量", "type": "text", "required": true, "maxLength": 20 },
          { "key": "unit_price", "label": "单价（含币种）", "type": "text", "required": true, "maxLength": 50 }
        ]
      }
    },
    { "key": "delivery_terms", "label": "交货与运输条款", "type": "textarea", "required": true, "maxLength": 1000 },
    { "key": "dispute_resolution", "label": "争议解决方式", "type": "textarea", "required": true, "maxLength": 1000 },
    { "key": "sign_date", "label": "签订日期", "type": "date", "required": true }
  ]
}', 'zh-Hans', 1, 4, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category), template_html = VALUES(template_html), field_schema = VALUES(field_schema), sort_order = VALUES(sort_order), status = VALUES(status), updated_at = VALUES(updated_at);

INSERT INTO doc_template (code, name, category, template_html, field_schema, lang, version, sort_order, status, created_at, updated_at)
VALUES ('IOU-001', '借条欠条', '合同文书', '<html>
<head>
<meta charset="utf-8" />
<!-- 借条没有官方统一模板，要素依《中华人民共和国民法典》第六百六十七条
     与最高人民法院关于审理民间借贷案件适用法律若干问题的规定拟定。
     本模板不是官方文本，交付前需人工核对一次（见《第 4 段工作流》§六 R3）。 -->
<style>
@page { size: A4; margin: 72pt 72pt 72pt 90pt; }
body { font-family: ''FangSong''; font-size: 16pt; line-height: 28pt; margin: 0; color: #000000; }
h1 { font-size: 22pt; text-align: center; font-weight: bold; margin: 0 0 24pt 0; line-height: 32pt; }
p { margin: 0 0 6pt 0; text-align: justify; }
div.notice { margin: 0 0 12pt 0; font-size: 12pt; line-height: 20pt; text-align: center; }
div.signature { margin-top: 36pt; line-height: 28pt; }
</style>
</head>
<body>
<h1>借　　条</h1>
<div class="notice">（非官方文本，由法泽拟定，仅供参考）</div>

<p>出借人：{{lender_name}}</p>
<p>借款人：{{borrower_name}}</p>
<p>借款人公民身份号码：{{borrower_id_card}}</p>

<p>今借到出借人人民币（大写）{{amount_text}}元整，小写{{amount_num}}元。</p>

<p>借款用途为借款人合法经营与生活需要，借款利息按{{interest_rate}}计算。</p>

<p>借款人应于{{repay_date}}前一次性偿还全部借款本金及利息。</p>

<p>担保方式：{{guarantee}}</p>

<p>借款人逾期还款的，应按约定利率支付逾期期间的利息，并承担出借人为实现债权支出的合理费用。</p>

<p>本借条自出借人交付借款之日起生效，一式两份，出借人与借款人各执一份。</p>

<div class="signature">
<p>出借人（签字）：{{lender_name}}</p>
<p>借款人（签字）：{{borrower_name}}</p>
<p>{{issue_date}}</p>
</div>
</body>
</html>
', '{
  "version": 1,
  "fields": [
    { "key": "lender_name", "label": "出借人姓名", "type": "text", "required": true, "maxLength": 30 },
    { "key": "borrower_name", "label": "借款人姓名", "type": "text", "required": true, "maxLength": 30 },
    { "key": "borrower_id_card", "label": "借款人身份证号", "type": "text", "required": true, "maxLength": 18 },
    { "key": "amount_text", "label": "借款金额（大写）", "type": "text", "required": true, "maxLength": 50 },
    { "key": "amount_num", "label": "借款金额（小写）", "type": "text", "required": true, "maxLength": 30 },
    { "key": "interest_rate", "label": "利率约定", "type": "text", "required": true, "maxLength": 100 },
    { "key": "repay_date", "label": "还款日期", "type": "date", "required": true },
    { "key": "guarantee", "label": "担保方式", "type": "textarea", "required": true, "maxLength": 500 },
    { "key": "issue_date", "label": "出具日期", "type": "date", "required": true }
  ]
}', 'zh-Hans', 1, 5, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category), template_html = VALUES(template_html), field_schema = VALUES(field_schema), sort_order = VALUES(sort_order), status = VALUES(status), updated_at = VALUES(updated_at);

