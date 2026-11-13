import { useCallback, useEffect, useState, type ReactNode } from "react";
import { invoke } from "@tauri-apps/api/core";
import { Activity, AlertCircle, Bot, Cable, CheckCircle2, CircleHelp, Command, Gamepad2, KeyRound, LayoutDashboard, Link2, ListChecks, LoaderCircle, MonitorSmartphone, Plus, RefreshCw, Settings2, ShieldCheck, Smartphone, TerminalSquare, Wifi } from "lucide-react";

type Section = "overview" | "devices" | "tasks" | "scripts" | "logs" | "settings";
type AdbDevice = { serial: string; state: string; model: string | null; product: string | null; device: string | null; transportId: string | null };
type DeviceDetails = { serial: string; model: string | null; androidVersion: string | null; screenResolution: string | null; foregroundActivity: string | null };
type AdbSnapshot = { available: boolean; adbPath: string | null; version: string | null; devices: AdbDevice[]; error: string | null };

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
  const [snapshot, setSnapshot] = useState<AdbSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [lastChecked, setLastChecked] = useState<string | null>(null);
  const [pairAddress, setPairAddress] = useState("");
  const [pairingCode, setPairingCode] = useState("");
  const [connectAddress, setConnectAddress] = useState("");
  const [actionBusy, setActionBusy] = useState<"pair" | "connect" | "disconnect" | null>(null);
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const [deviceDetails, setDeviceDetails] = useState<DeviceDetails | null>(null);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [notice, setNotice] = useState("正在初始化 ADB 设备通道…");
  const current = nav.find(item => item.id === section)!;

  const refreshDevices = useCallback(async () => {
    setLoading(true);
    try {
      const result = await invoke<AdbSnapshot>("get_adb_snapshot");
      setSnapshot(result);
      setLastChecked(new Date().toLocaleTimeString());
      setNotice(result.error ?? (result.devices.length ? `已发现 ${result.devices.length} 台 ADB 设备。` : "ADB 已就绪，暂未发现已授权设备。"));
    } catch (error) {
      setNotice(`读取 ADB 状态失败：${String(error)}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refreshDevices(); }, [refreshDevices]);


  const pairWirelessDevice = async () => {
    setActionBusy("pair");
    setActionMessage(null);
    try {
      const result = await invoke<string>("adb_pair", { address: pairAddress.trim(), pairingCode });
      setActionMessage(result);
      setPairingCode("");
      await refreshDevices();
    } catch (error) {
      setActionMessage(`配对失败：${String(error)}`);
    } finally {
      setActionBusy(null);
    }
  };

  const connectWirelessDevice = async () => {
    setActionBusy("connect");
    setActionMessage(null);
    try {
      const result = await invoke<string>("adb_connect", { address: connectAddress.trim() });
      setActionMessage(result);
      await refreshDevices();
    } catch (error) {
      setActionMessage(`连接失败：${String(error)}`);
    } finally {
      setActionBusy(null);
    }
  };


  const disconnectWirelessDevice = async (serial: string) => {
    setActionBusy("disconnect");
    setActionMessage(null);
    try {
      const result = await invoke<string>("adb_disconnect", { address: serial });
      setActionMessage(result);
      await refreshDevices();
    } catch (error) {
      setActionMessage("断开失败：" + String(error));
    } finally {
      setActionBusy(null);
    }
  };

  const inspectDevice = async (serial: string) => {
    setDetailsLoading(true);
    setDeviceDetails(null);
    setSection("devices");
    setActionMessage(null);
    try {
      const result = await invoke<DeviceDetails>("get_adb_device_details", { serial });
      setDeviceDetails(result);
    } catch (error) {
      setActionMessage("读取设备信息失败：" + String(error));
    } finally {
      setDetailsLoading(false);
    }
  };

  const onlineCount = snapshot?.devices.filter(device => device.state === "device").length ?? 0;
  const connectionLabel = !snapshot ? "检测中" : !snapshot.available ? "不可用" : onlineCount ? "已连接" : "待连接";

  return <div className="shell">
    <aside className="sidebar">
      <div className="brand"><div className="brand-mark"><Gamepad2 size={21}/></div><div><b>GamePilot</b><small>DEVICE CONTROL</small></div></div>
      <div className="workspace-label">工作空间</div>
      <div className="workspace"><span className="workspace-dot"/> 我的养老时光 <span className="chevron">⌄</span></div>
      <nav>{nav.map(item => {
        const Icon = item.icon;
        return <button key={item.id} className={section === item.id ? "nav-item active" : "nav-item"} onClick={() => setSection(item.id)}><Icon size={17}/>{item.label}{item.id === "devices" && <span className="nav-count">{snapshot?.devices.length ?? "—"}</span>}</button>;
      })}</nav>
      <div className="sidebar-bottom"><div className="connection"><span className={onlineCount ? "status-dot online" : "status-dot offline"}/>{snapshot?.available ? `ADB ${connectionLabel}` : "ADB 通道未就绪"}</div><div className="version">控制台 v0.1.0 · Preview</div></div>
    </aside>
    <main className="main">
      <header className="topbar"><div className="breadcrumb">工作空间 <span>/</span> {current.label}</div><div className="top-actions"><span className="platform"><MonitorSmartphone size={15}/> 桌面端</span><button className="icon-button" title="帮助"><CircleHelp size={18}/></button><div className="avatar">GP</div></div></header>
      <section className="content">
        <div className="page-heading"><div><div className="eyebrow">GAMEPILOT / CONTROL CENTER</div><h1>{current.label}</h1><p>{section === "overview" ? "统一管理 Android 真机、模拟器与自动化任务。" : section === "devices" ? "扫描 USB、无线调试和模拟器 ADB 连接。" : section === "tasks" ? "创建、执行和追踪自动化任务。" : section === "scripts" ? "维护可复用的自动化流程与游戏适配器。" : section === "logs" ? "查看设备事件、任务步骤和执行结果。" : "配置连接方式、权限和运行环境。"}</p></div><button className="primary" onClick={() => section === "devices" ? void refreshDevices() : setSection("devices")} disabled={loading}>{loading ? <LoaderCircle size={16} className="spin"/> : section === "devices" ? <RefreshCw size={16}/> : <Plus size={16}/>} {section === "devices" ? "刷新设备" : "设备管理"}</button></div>
        <div className={snapshot?.error ? "notice warning" : "notice"}>{snapshot?.error ? <AlertCircle size={16}/> : <ShieldCheck size={16}/>}<span>{notice}</span></div>
        {section === "overview" ? <>
          <div className="stats-grid">
            <Stat icon={<Smartphone/>} label="在线设备" value={String(onlineCount)} detail={snapshot?.devices.length ? `发现 ${snapshot.devices.length} 台设备` : "等待设备连接"} />
            <Stat icon={<Activity/>} label="运行中任务" value="0" detail="任务引擎尚未接入" />
            <Stat icon={<ListChecks/>} label="今日完成" value="0" detail="暂无运行记录" />
            <Stat icon={<Wifi/>} label="ADB 状态" value={connectionLabel} detail={snapshot?.version ?? "等待检测"} />
          </div>
          <div className="section-grid">
            <div className="panel"><div className="panel-title"><div><h2>设备列表</h2><p>ADB 实时发现结果</p></div><button className="text-button" onClick={() => setSection("devices")}>查看全部 →</button></div>
              {snapshot?.devices.length ? <DeviceList devices={snapshot.devices.slice(0, 4)} onDisconnect={disconnectWirelessDevice} onDetails={inspectDevice} busy={actionBusy === "disconnect"}/> : <div className="empty-state"><div className="empty-icon"><Cable size={24}/></div><b>{snapshot?.available ? "还没有连接设备" : "尚未找到 ADB"}</b><span>{snapshot?.available ? "通过 USB 调试或无线调试连接手机，也可以接入 Android 模拟器。" : "安装 Android SDK Platform-Tools，并将 adb 加入 PATH 后刷新。"}</span><button className="secondary" onClick={() => setSection("devices")}>设备连接说明</button></div>}
            </div>
            <div className="panel"><div className="panel-title"><div><h2>快速开始</h2><p>完成自动化运行前的准备</p></div></div><div className="steps"><Step n="01" title="连接 Android 设备" desc="ADB / 无线调试" done={onlineCount > 0}/><Step n="02" title="授权自动化能力" desc="无障碍服务 / 可选 Shizuku" done={false}/><Step n="03" title="配置游戏适配器" desc="我的养老时光" done={false}/><Step n="04" title="创建并运行任务" desc="识别、操作、验证、记录" done={false}/></div></div>
          </div>
          <div className="panel bottom-panel"><div className="panel-title"><div><h2>系统模块</h2><p>桌面端负责编排，Android 执行端负责设备操作</p></div></div><div className="module-grid"><Module icon={<Command/>} title="ADB 设备通道" desc="已接入设备发现"/><Module icon={<ShieldCheck/>} title="Shizuku 增强" desc="可选系统 API 授权"/><Module icon={<Bot/>} title="自动化引擎" desc="待接入无障碍与任务状态机"/><Module icon={<Activity/>} title="视觉识别" desc="待接入截图、OCR 与图像匹配"/></div></div>
        </> : section === "devices" ? <div className="panel device-panel">
          <div className="panel-title"><div><h2>ADB 设备</h2><p>{snapshot?.adbPath ? `执行文件：${snapshot.adbPath}` : "自动探测 Android SDK Platform-Tools 与 PATH"}</p></div><span className={snapshot?.available ? "state-pill ready" : "state-pill"}>{snapshot?.available ? "ADB 可用" : "ADB 未就绪"}</span></div>
          <div className="adb-meta"><span>版本：{snapshot?.version ?? "—"}</span><span>最近扫描：{lastChecked ?? "—"}</span><span>设备数：{snapshot?.devices.length ?? 0}</span></div>
          <div className="wireless-grid">
            <form className="wireless-form" onSubmit={event => { event.preventDefault(); void pairWirelessDevice(); }}>
              <div className="wireless-heading"><KeyRound size={16}/><b>无线调试配对</b></div>
              <p>在手机「开发者选项 → 无线调试 → 使用配对码配对设备」中查看配对 IP 和端口。</p>
              <label>配对地址（IP:配对端口）</label>
              <input value={pairAddress} onChange={event => setPairAddress(event.target.value)} placeholder="例如 192.168.1.20:37123" autoComplete="off"/>
              <label>六位配对码</label>
              <input value={pairingCode} onChange={event => setPairingCode(event.target.value)} placeholder="输入手机显示的配对码" type="password" inputMode="numeric" autoComplete="off"/>
              <button className="secondary" type="submit" disabled={!snapshot?.available || !pairAddress.trim() || !pairingCode || actionBusy !== null}>{actionBusy === "pair" ? <LoaderCircle size={14} className="spin"/> : <KeyRound size={14}/>} 开始配对</button>
            </form>
            <form className="wireless-form" onSubmit={event => { event.preventDefault(); void connectWirelessDevice(); }}>
              <div className="wireless-heading"><Link2 size={16}/><b>连接无线设备</b></div>
              <p>配对成功后，在手机无线调试页面查看「IP 地址和端口」，注意这是连接端口，不是配对端口。</p>
              <label>连接地址（IP:连接端口）</label>
              <input value={connectAddress} onChange={event => setConnectAddress(event.target.value)} placeholder="例如 192.168.1.20:42817" autoComplete="off"/>
              <div className="wireless-spacer"/>
              <button className="secondary" type="submit" disabled={!snapshot?.available || !connectAddress.trim() || actionBusy !== null}>{actionBusy === "connect" ? <LoaderCircle size={14} className="spin"/> : <Wifi size={14}/>} 连接设备</button>
            </form>
          </div>
          {actionMessage && <div className="action-message"><AlertCircle size={14}/><span>{actionMessage}</span></div>}
          {snapshot?.devices.length ? {detailsLoading && <div className="action-message"><LoaderCircle size={14} className="spin"/>正在读取设备信息…</div>}
          {deviceDetails && <div className="details-panel"><div className="panel-title"><div><h2>设备详情</h2><p>{deviceDetails.serial}</p></div><button className="text-button" onClick={() => setDeviceDetails(null)}>收起</button></div><div className="details-grid"><Detail label="设备型号" value={deviceDetails.model}/><Detail label="Android 版本" value={deviceDetails.androidVersion}/><Detail label="屏幕分辨率" value={deviceDetails.screenResolution}/><Detail label="前台 Activity" value={deviceDetails.foregroundActivity}/></div></div>}
          <DeviceList devices={snapshot.devices} onDisconnect={disconnectWirelessDevice} onDetails={inspectDevice} busy={actionBusy === "disconnect"}/> : <div className="empty-state device-empty"><div className="empty-icon"><Smartphone size={24}/></div><b>{snapshot?.available ? "没有检测到设备" : "ADB 尚不可用"}</b><span>{snapshot?.error ?? "连接手机并开启开发者选项中的 USB 调试，或按上方步骤完成无线配对和连接。"}</span></div>}
          <div className="callout"><b>连接提示</b><span>USB：手机开启开发者选项和 USB 调试，连接电脑并在手机上确认 RSA 授权。</span><span>无线：配对端口与连接端口不同；先配对，再使用手机页面显示的连接端口执行连接。</span><span>安全：配对码仅用于本次配对，不持久化；所有操作均通过固定 ADB 子命令执行，不提供任意 Shell。</span></div>
        </div> : <div className="panel section-placeholder"><div className="empty-icon">{(() => { const Icon = current.icon; return <Icon size={25}/>; })()}</div><h2>{current.label}模块</h2><p>页面骨架已建立，等待对应设备服务和数据接口接入。</p></div>}
      </section>
    </main>
  </div>;
}

function DeviceList({ devices, onDisconnect, onDetails, busy = false }: { devices: AdbDevice[]; onDisconnect?: (serial: string) => void; onDetails?: (serial: string) => void; busy?: boolean }) {
  return <div className="device-list">{devices.map(device => {
    const wireless = device.serial.includes(":");
    return <div className="device-row" key={device.serial}><div className="device-icon"><Smartphone size={18}/></div><div className="device-main"><b>{device.model ?? device.device ?? device.serial}</b><small>{device.serial}{device.product ? " · " + device.product : ""}</small></div><span className={device.state === "device" ? "device-state connected" : "device-state"}>{device.state === "device" ? <><CheckCircle2 size={13}/> 已授权</> : device.state === "unauthorized" ? "待授权" : device.state === "offline" ? "离线" : device.state}</span>{onDetails && device.state === "device" && <button className="details-button" type="button" onClick={() => onDetails(device.serial)}>详情</button>}{wireless && onDisconnect && <button className="disconnect-button" type="button" disabled={busy} onClick={() => onDisconnect(device.serial)}>断开</button>}</div>;
  })}</div>;
}
function Detail({ label, value }: { label: string; value: string | null }) {
  return <div className="detail-item"><small>{label}</small><b>{value || "暂不可用"}</b></div>;
}
function Stat({ icon, label, value, detail }: { icon: ReactNode; label: string; value: string; detail: string }) {
  return <div className="stat-card"><div className="stat-top"><span>{label}</span><i>{icon}</i></div><strong>{value}</strong><small>{detail}</small></div>;
}
function Step({ n, title, desc, done }: { n: string; title: string; desc: string; done: boolean }) {
  return <div className="step"><span className={done ? "step-number done" : "step-number"}>{n}</span><div><b>{title}</b><small>{desc}</small></div><span className="step-state">{done ? "完成" : "待处理"}</span></div>;
}
function Module({ icon, title, desc }: { icon: React.ReactNode; title: string; desc: string }) {
  return <div className="module"><i>{icon}</i><div><b>{title}</b><small>{desc}</small></div></div>;
}
export default App;
