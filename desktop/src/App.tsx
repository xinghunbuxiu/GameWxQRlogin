import { useState } from "react";
import { Activity, Bot, Cable, CircleHelp, Command, Gamepad2, LayoutDashboard, ListChecks, MonitorSmartphone, Play, Plus, Settings2, ShieldCheck, Smartphone, TerminalSquare, Wifi } from "lucide-react";

type Section = "overview" | "devices" | "tasks" | "scripts" | "logs" | "settings";

const nav: { id: Section; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "overview", label: "总览", icon: LayoutDashboard },
  { id: "devices", label: "设备管理", icon: Smartphone },
  { id: "tasks", label: "任务中心", icon: ListChecks },
  { id: "scripts", label: "自动化脚本", icon: Bot },
  { id: "logs", label: "运行日志", icon: TerminalSquare },
  { id: "settings", label: "设置", icon: Settings2 }
];

function App() {
  const [section, setSection] = useState<Section>("overview");
  const [notice, setNotice] = useState("桌面控制台原型已启动。设备通道尚未连接。");
  const current = nav.find(item => item.id === section)!;

  return <div className="shell">
    <aside className="sidebar">
      <div className="brand"><div className="brand-mark"><Gamepad2 size={21}/></div><div><b>GamePilot</b><small>DEVICE CONTROL</small></div></div>
      <div className="workspace-label">工作空间</div>
      <div className="workspace"><span className="workspace-dot"/> 我的养老时光 <span className="chevron">⌄</span></div>
      <nav>{nav.map(item => {
        const Icon = item.icon;
        return <button key={item.id} className={section === item.id ? "nav-item active" : "nav-item"} onClick={() => setSection(item.id)}><Icon size={17}/>{item.label}{item.id === "devices" && <span className="nav-count">0</span>}</button>;
      })}</nav>
      <div className="sidebar-bottom"><div className="connection"><span className="status-dot offline"/>设备通道未连接</div><div className="version">控制台 v0.1.0 · Preview</div></div>
    </aside>
    <main className="main">
      <header className="topbar"><div className="breadcrumb">工作空间 <span>/</span> {current.label}</div><div className="top-actions"><span className="platform"><MonitorSmartphone size={15}/> 桌面端</span><button className="icon-button" title="帮助"><CircleHelp size={18}/></button><div className="avatar">GP</div></div></header>
      <section className="content">
        <div className="page-heading"><div><div className="eyebrow">GAMEPILOT / CONTROL CENTER</div><h1>{current.label}</h1><p>{section === "overview" ? "统一管理 Android 真机、模拟器与自动化任务。" : section === "devices" ? "管理已授权连接的 Android 手机和模拟器。" : section === "tasks" ? "创建、执行和追踪自动化任务。" : section === "scripts" ? "维护可复用的自动化流程与游戏适配器。" : section === "logs" ? "查看设备事件、任务步骤和执行结果。" : "配置连接方式、权限和运行环境。"}</p></div><button className="primary" onClick={() => { setSection("devices"); setNotice("请先连接 Android 设备；当前版本尚未接入真实 ADB 通道。"); }}><Plus size={16}/> 连接设备</button></div>
        <div className="notice"><ShieldCheck size={16}/><span>{notice}</span></div>
        {section === "overview" ? <>
          <div className="stats-grid">
            <Stat icon={<Smartphone/>} label="在线设备" value="0" detail="等待连接" />
            <Stat icon={<Activity/>} label="运行中任务" value="0" detail="暂无执行任务" />
            <Stat icon={<ListChecks/>} label="今日完成" value="0" detail="暂无运行记录" />
            <Stat icon={<Wifi/>} label="连接状态" value="离线" detail="ADB 通道未初始化" />
          </div>
          <div className="section-grid">
            <div className="panel"><div className="panel-title"><div><h2>设备列表</h2><p>已连接的 Android 设备</p></div><button className="text-button" onClick={() => setSection("devices")}>查看全部 →</button></div><div className="empty-state"><div className="empty-icon"><Cable size={24}/></div><b>还没有连接设备</b><span>通过 USB 调试或无线调试连接手机，也可以接入 Android 模拟器。</span><button className="secondary" onClick={() => setSection("devices")}><Plus size={15}/> 添加第一台设备</button></div></div>
            <div className="panel"><div className="panel-title"><div><h2>快速开始</h2><p>完成自动化运行前的准备</p></div></div><div className="steps"><Step n="01" title="连接 Android 设备" desc="ADB / 无线调试" done={false}/><Step n="02" title="授权自动化能力" desc="无障碍服务 / 可选 Shizuku" done={false}/><Step n="03" title="配置游戏适配器" desc="我的养老时光" done={false}/><Step n="04" title="创建并运行任务" desc="识别、操作、验证、记录" done={false}/></div></div>
          </div>
          <div className="panel bottom-panel"><div className="panel-title"><div><h2>系统模块</h2><p>桌面端负责编排，Android 执行端负责设备操作</p></div></div><div className="module-grid"><Module icon={<Command/>} title="ADB 设备通道" desc="连接、安装、启动与日志"/><Module icon={<ShieldCheck/>} title="Shizuku 增强" desc="可选系统 API 授权"/><Module icon={<Bot/>} title="自动化引擎" desc="无障碍与任务状态机"/><Module icon={<Activity/>} title="视觉识别" desc="截图、OCR 与图像匹配"/></div></div>
        </> : <div className="panel section-placeholder"><div className="empty-icon"><current.icon size={25}/></div><h2>{current.label}模块</h2><p>页面骨架已建立，等待对应设备服务和数据接口接入。</p>{section === "devices" && <div className="callout"><b>连接方式规划</b><span>USB ADB · 无线 ADB · 模拟器 ADB</span><span>Shizuku 作为 Android 端可选增强，不作为连接设备的替代品。</span></div>}</div>}
      </section>
    </main>
  </div>;
}

function Stat({ icon, label, value, detail }: { icon: React.ReactNode; label: string; value: string; detail: string }) {
  return <div className="stat-card"><div className="stat-top"><span>{label}</span><i>{icon}</i></div><strong>{value}</strong><small>{detail}</small></div>;
}
function Step({ n, title, desc, done }: { n: string; title: string; desc: string; done: boolean }) {
  return <div className="step"><span className={done ? "step-number done" : "step-number"}>{n}</span><div><b>{title}</b><small>{desc}</small></div><span className="step-state">{done ? "完成" : "待处理"}</span></div>;
}
function Module({ icon, title, desc }: { icon: React.ReactNode; title: string; desc: string }) {
  return <div className="module"><i>{icon}</i><div><b>{title}</b><small>{desc}</small></div></div>;
}
export default App;