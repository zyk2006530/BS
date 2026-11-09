"""Static interface prototypes, generated with the Python standard library.
Run from any directory. No existing drawing or application module is imported.
Every text segment has an explicit owner and a conservative width envelope.
"""
from html import escape
from pathlib import Path
import unicodedata
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent / 'images'
INK, BLUE, PALE, LINE = '#243448', '#195a94', '#edf4fa', '#bccbd9'
MUTED, WHITE = '#60758b', '#ffffff'
FONT = 'Microsoft YaHei, Noto Sans SC, sans-serif'


def text_width(text, size):
    return sum(size * (1 if unicodedata.east_asian_width(ch) in 'WF'
                       or ch in 'WM@' else 0.7) for ch in text)


class Canvas:
    def __init__(self, title, name):
        self.w, self.h, self.name = 1000, 600, name
        self.checks = []
        self.parts = [
            '<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="600" '
            'viewBox="0 0 1000 600" role="img" aria-labelledby="title desc">',
            f'<title id="title">{escape(title)}</title>',
            '<desc id="desc">静态界面设计，非真实实现截图；全部人员、业务和统计数值均为示例。</desc>',
            '<rect width="100%" height="100%" fill="white"/>']
        self.rect(8, 8, 984, 584, WHITE)
        self.rect(8, 8, 984, 62, BLUE)
        self.text(28, 17, 944, 44, title, 26, WHITE)
        self.text(28, 557, 944, 30, '界面原型·示例数据', 20, MUTED)

    def bounds(self, x, y, w, h):
        assert 0 <= x <= x+w <= self.w, (self.name, x, w)
        assert 0 <= y <= y+h <= self.h, (self.name, y, h)

    def rect(self, x, y, w, h, fill=PALE, radius=7):
        self.bounds(x, y, w, h)
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" '
                          f'rx="{radius}" fill="{fill}" stroke="{LINE}"/>')

    def line(self, x1, y1, x2, y2, color=LINE):
        self.bounds(min(x1, x2), min(y1, y2), abs(x2-x1), abs(y2-y1))
        self.parts.append(f'<path d="M{x1},{y1} L{x2},{y2}" fill="none" '
                          f'stroke="{color}" stroke-width="1.5"/>')

    def text(self, x, y, w, h, value, size=20, color=INK, center=False):
        self.bounds(x, y, w, h)
        assert size >= 20, (self.name, value, 'small type')
        width = text_width(value, size)
        tx = x+(w-width)/2 if center else x
        baseline = y+h/2+size*0.35
        envelope = (tx, baseline-size*0.95, width, size*1.2)
        assert width+1 <= w, (self.name, value, round(width, 1), w)
        assert envelope[1] >= y and envelope[1]+envelope[3] <= y+h, (self.name, value)
        self.checks.append((value, envelope, (x, y, w, h)))
        self.parts.append(f'<text x="{tx:.2f}" y="{baseline:.2f}" '
                          f'font-family="{FONT}" font-size="{size}" '
                          f'fill="{color}">{escape(value)}</text>')

    def panel(self, x, y, w, h, title):
        self.rect(x, y, w, h, WHITE)
        self.rect(x, y, w, 46)
        self.text(x+14, y+5, w-28, 36, title, 22, BLUE)

    def row(self, x, y, w, value, fill=WHITE, color=INK):
        self.rect(x, y, w, 38, fill)
        self.text(x+10, y+3, w-20, 32, value, 20, color)

    def button(self, x, y, w, value, primary=False):
        self.rect(x, y, w, 38, BLUE if primary else PALE)
        self.text(x+8, y+3, w-16, 32, value, 20, WHITE if primary else BLUE, True)

    def note(self, x, y, w, value, color=MUTED):
        self.text(x, y, w, 30, value, 20, color)

    def validate(self):
        for value, (x, y, w, h), (bx, by, bw, bh) in self.checks:
            assert bx <= x and by <= y and x+w <= bx+bw and y+h <= by+bh, value
        ET.fromstring('\n'.join(self.parts+['</svg>']))

    def save(self):
        self.validate()
        (OUT/self.name).write_text('\n'.join(self.parts+['</svg>']), encoding='utf-8')
        print(f'{self.name}: 1000x600; {len(self.checks)} text bounds passed')


def account():
    c = Canvas('账号与个人资料', 'fig08-account.svg')
    for x, title in [(28, '登录 / 注册'), (350, '注册确认'), (672, '个人资料 / 改密')]:
        c.panel(x, 94, 300, 444, title)
    c.note(44, 146, 268, '登录   |   注册', BLUE)
    for y, s in [(184, '账号：liming01'), (226, '密码：••••••••'),
                 (268, '姓名：李明'), (310, '部门：研发部  ▾'),
                 (352, '邮箱：lm@ex.cn')]:
        c.row(44, y, 268, s)
    c.button(44, 402, 268, '提交注册', True)
    c.note(44, 450, 268, '已有账号：切换登录')
    c.note(44, 494, 268, '错误：账号或密码不匹配')
    c.row(366, 158, 268, '状态：待确认', PALE, BLUE)
    for y, s in [(214, '姓名：李明'), (254, '申请部门：研发部'),
                 (308, '等待管理员核对资料'), (348, '确认前不可提交申请'),
                 (478, '确认后进入工作台')]:
        c.note(366, y, 268, s)
    c.button(366, 412, 268, '刷新确认状态')
    c.note(688, 150, 268, '姓名：李明   部门：研发')
    for y, s in [(190, '邮箱：lm@ex.cn'), (292, '原密码：••••••••'),
                 (338, '新密码：••••••••'), (384, '确认密码：••••••••')]:
        c.row(688, y, 268, s)
    c.button(688, 236, 268, '保存资料')
    c.button(688, 440, 268, '修改密码', True)
    c.note(688, 490, 268, '修改后重新登录')
    return c


def workbench():
    c = Canvas('申请工作台', 'fig09-workbench.svg')
    for x, s in [(28, '我的申请  12'), (250, '待办  3'), (472, '已办  18')]:
        c.button(x, 91, 206, s, x == 250)
    c.note(704, 95, 268, '当前显示：待办', BLUE)
    c.row(28, 144, 216, '类型：全部  ▾')
    c.row(260, 144, 216, '部门：研发  ▾')
    c.row(492, 144, 274, '提交日：近 30 天  ▾')
    c.button(784, 144, 188, '查询 / 重置')
    c.row(28, 188, 216, '状态：全部  ▾')
    c.row(260, 188, 712, '关键词：标题 / 编号  [设备]')
    c.panel(28, 238, 944, 170, '当前待办')
    for x, w, s in [(44, 280, '申请编号 / 业务'), (350, 150, '申请人'),
                    (514, 240, '当前节点'), (862, 94, '操作')]:
        c.note(x, 286, w, s)
    c.line(44, 317, 956, 317)
    for y, code, name, node in [(321, 'BX-021  差旅报销', '李明', '财务审核'),
                               (369, 'CG-018  设备采购', '王宁', '部门审核')]:
        c.note(44, y, 280, code)
        c.note(350, y, 150, name)
        c.note(514, y, 200, node)
        c.button(862, y-3, 94, '处理')
    c.row(726, 318, 120, '超时 26h', PALE, BLUE)
    c.note(28, 422, 530, '共 3 条；每页 2 条')
    c.button(590, 418, 158, '上一页')
    c.button(764, 418, 208, '1 / 2   下一页')
    c.panel(28, 470, 454, 80, '空态 · 切换至我的申请')
    c.note(44, 517, 420, '暂无匹配记录   清空筛选 / 新建')
    c.panel(502, 470, 470, 80, '加载失败 · 切换至已办')
    c.note(518, 517, 438, '列表未加载，保留筛选   [重试]')
    return c


def admin():
    c = Canvas('用户与权限管理', 'fig10-admin.svg')
    c.panel(28, 94, 454, 202, '用户确认')
    c.row(44, 150, 422, '李明  /  研发部  /  待确认')
    c.note(44, 198, 422, '核对姓名、部门与账号归属')
    c.button(44, 242, 182, '确认启用', True)
    c.button(242, 242, 224, '拒绝并填写原因')
    c.panel(502, 94, 470, 202, '部门负责人任命')
    c.row(518, 150, 438, '部门：研发部  ▾')
    c.row(518, 196, 438, '现任：王宁 → 新任：陈工  ▾')
    c.button(518, 242, 438, '核对待办后确认任命')
    c.panel(28, 312, 454, 226, '角色与显式授权')
    c.row(44, 368, 422, '用户：赵敏  /  角色：财务审核')
    c.row(44, 414, 422, '正文阅读｜研发部｜至12-31')
    c.note(44, 460, 422, '能力、范围与期限分别选择')
    c.button(44, 496, 422, '新增授权 / 保存角色')
    c.panel(502, 312, 470, 226, '停用账号')
    c.row(518, 368, 438, '王宁：负责人 1 项；待办 2 项')
    c.row(518, 414, 438, '接收人：陈工  ▾  [交接核对]')
    c.note(518, 460, 438, '有责任或待办时禁止直接停用')
    c.button(518, 496, 438, '先完成交接，再确认停用')
    return c


def config():
    c = Canvas('固定流程与配置审计', 'fig11-config.svg')
    c.panel(28, 94, 554, 300, '固定审批配置')
    for y, s in [(151, '财务审核：主选 赵敏  /  备选 周平'),
                 (199, '企业负责人：主选 孙总  /  备选 吴总'),
                 (247, '采购/报销 >5,000；差旅 >20,000 元'),
                 (295, '自动催办：24h  /  每次分配提醒一次')]:
        c.row(44, y, 522, s)
    c.button(44, 347, 522, '校验并保存新配置版本', True)
    c.panel(602, 94, 370, 300, '配置摘要')
    for y, s in [(151, '范围：采购 / 差旅 / 报销'), (194, '部门审核 → 财务审核'),
                 (237, '超过阈值 → 企业负责人'), (280, '主选为申请人时改用备选'),
                 (323, '等于阈值不增加节点'), (357, '提交时固化版本与人员')]:
        c.note(618, y, 338, s)
    c.panel(28, 410, 944, 128, '配置版本审计查询')
    c.note(44, 461, 900, '版本：v3  ▾      操作人：全部  ▾      日期：近 30 天  ▾      [查询]')
    c.note(44, 500, 900, 'v3  |  管理员  |  阈值 3,000 → 5,000 元  |  11-09 10:30  |  查看变更')
    return c


def travel_custom():
    c = Canvas('差旅申请与设备借用', 'fig12-travel-custom.svg')
    c.panel(28, 94, 454, 444, '差旅申请')
    for y, s in [(151, '出差地点：杭州 → 上海'), (197, '出发：12-01  /  返回：12-03'),
                 (243, '预算：2,400 元  /  项目：联调'), (289, '出差事由：现场设备联调'),
                 (335, '附件：行程安排.pdf  [预览]')]:
        c.row(44, y, 422, s)
    c.note(44, 391, 422, '审批：部门 → 财务')
    c.note(44, 427, 422, '超过金额阈值后增加企业负责人')
    c.button(44, 488, 194, '保存草稿')
    c.button(254, 488, 212, '校验并提交', True)
    c.panel(502, 94, 470, 444, '设备借用 · 发布版 v1')
    for y, s in [(151, '单行文本｜设备：投影仪 A'), (197, '多行文本｜说明：会议演示借用'),
                 (243, '数字｜数量：1（最小值 1）'), (289, '日期｜归还：2026-12-03'),
                 (335, '单选｜用途：研发  ▾'), (381, '附件｜材料：借用说明.pdf')]:
        c.row(518, y, 438, s)
    c.note(518, 427, 438, '审批：部门负责人 → 资产审核')
    c.button(518, 488, 194, '保存草稿')
    c.button(728, 488, 228, '校验并提交', True)
    return c


def notice_stats():
    c = Canvas('通知与业务统计', 'fig13-notice-stats.svg')
    c.panel(28, 94, 362, 444, '通知中心')
    c.note(44, 146, 330, '全部  |  未读 2', BLUE)
    c.row(44, 190, 330, '待审批：报销 BX-021', PALE)
    c.note(54, 233, 310, '10:30  新待办  [查看]')
    c.row(44, 285, 330, '进度：差旅 CL-012 通过')
    c.note(54, 328, 310, '09:10  审批完成  [查看]')
    c.button(44, 387, 330, '全部标为已读')
    c.note(44, 449, 330, '站内通知保留已读状态')
    c.note(44, 489, 330, '邮件发送失败不改变审批结果')
    c.panel(410, 94, 562, 444, '个人 / 部门统计')
    c.button(426, 151, 150, '个人')
    c.button(590, 151, 150, '部门', True)
    c.note(758, 155, 198, '近 30 天  ▾')
    c.note(426, 200, 530, '个人状态：待办 3 / 草稿 2 / 已通过 5')
    c.note(426, 236, 530, '研发部 · 申请数量（单）')
    # Real columns share a zero baseline; scale is 10 pixels per order.
    c.line(466, 280, 466, 440, MUTED)
    c.line(466, 440, 946, 440, MUTED)
    for n in (0, 6, 12):
        yy = 440-n*10
        c.text(426, yy-15, 32, 30, str(n))
        if n:
            c.line(466, yy, 946, yy)
    for x, value, label in [(490, 12, '采购'), (604, 8, '差旅'),
                             (718, 6, '报销'), (832, 4, '自定义')]:
        c.rect(x, 440-value*10, 60, value*10, BLUE, 0)
        c.text(x, 440-value*10-34, 60, 30, str(value), 20, BLUE, True)
        c.text(x-10, 450, 80, 30, label, 20, INK, True)
    c.note(426, 496, 530, '数值仅为示意，非测试结果')
    return c


def mobile_extra():
    c = Canvas('移动端账号与业务办理', 'fig14-mobile-extra.svg')
    for x, title in [(28, '登录 / 注册'), (268, '申请 / 通知'),
                     (508, '设备借用'), (748, '票据 / 进度')]:
        c.panel(x, 94, 224, 444, title)
    c.note(40, 150, 200, '登录 | 注册', BLUE)
    for y, s in [(176, '账号：liming01'), (220, '密码：••••••'),
                 (264, '姓名：李明'), (308, '部门：研发  ▾'),
                 (352, '邮箱：lm@ex.cn')]:
        c.row(40, y, 200, s)
    c.button(40, 410, 200, '提交注册', True)
    c.note(40, 460, 200, '待确认：等待核对')
    c.note(40, 500, 200, '已有账号：登录')
    c.row(280, 150, 200, '我的申请  待办 3', PALE)
    c.row(280, 204, 200, '差旅 CL-012')
    c.note(280, 249, 200, '审批中  [进度]')
    c.button(280, 291, 200, '新建申请', True)
    c.note(280, 345, 200, '通知  未读 2', BLUE)
    c.row(280, 387, 200, '报销待审批')
    c.note(280, 432, 200, '10:30  [查看]')
    c.button(280, 482, 200, '标为已读')
    for y, s in [(150, '设备：投影仪 A'), (196, '数量：1'),
                 (242, '归还：12-03'), (288, '用途：研发  ▾')]:
        c.row(520, y, 200, s)
    c.note(520, 338, 200, '材料：借用说明')
    c.button(520, 380, 200, '选择文件 / 拍照')
    c.note(520, 434, 200, '部门 → 资产审核')
    c.button(520, 482, 200, '校验并提交', True)
    c.rect(760, 150, 200, 178, WHITE)
    c.text(770, 158, 180, 30, '车票.pdf', 20, BLUE)
    c.line(778, 204, 942, 204)
    c.note(776, 212, 168, '杭州 → 上海')
    c.note(776, 251, 168, '票面金额 235元')
    c.note(776, 290, 168, '1 / 1 页')
    c.note(760, 344, 200, '审批进度', BLUE)
    c.row(760, 385, 200, '● 部门审核通过')
    c.row(760, 431, 200, '○ 财务审核待办')
    c.button(760, 482, 200, '返回申请详情')
    return c


def main():
    canvases = [account(), workbench(), admin(), config(), travel_custom(),
                notice_stats(), mobile_extra()]
    # Validate all seven before writing the first output.
    for c in canvases:
        c.validate()
    OUT.mkdir(exist_ok=True)
    for c in canvases:
        c.save()
    print(f'Validated {len(canvases)} static prototypes; '
          f'{sum(len(c.checks) for c in canvases)} text bounds; minimum font 20px.')


if __name__ == '__main__':
    main()
