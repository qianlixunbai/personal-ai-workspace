# X1 跨应用助手：GitHub 定点借鉴记录

调研日期：2026-10-28；Workspace 基线：`ad0913dcf3c6ea918ad83665c2cdf0e7c90995b3`。
只通过 GitHub 读取下列固定版本的相关文件；未执行外部安装器/脚本，未使用 Firecrawl，未复制外部源码。

| 项目 / 审阅版本 | 实际文件、函数 | 适用性与取舍 | 许可证 / 复用边界 |
|---|---|---|---|
| [PowerToys](https://github.com/microsoft/PowerToys/tree/9922e7eed1ba8cb4e7e6ec8cd9ff4524b9c105bc) | `src/modules/launcher/PowerLauncher/MainWindow.xaml.cs`：`ProcessWindowMessages`、`OnSourceInitialized`、`BringProcessToForeground`；`MainWindow.xaml` 的 Escape binding 与 `ViewModel/MainViewModel.cs` 的 `EscCommand`、`Hide`；`src/common/interop/HotkeyManager.cpp`：`RegisterHotkey`、`KeyboardEventProc` | 借鉴 WPF 消息窗口、WM_HOTKEY、Esc 隐藏和复用窗口。拒绝通用 keyboard hook、dummy SendInput 和强制抢前台方案。Workspace 已有 RegisterHotKey，不需要引入 PowerToys 依赖。 | 根 LICENSE 为 MIT；若未来复制需保留许可及版权。本次仅借鉴交互思路。 |
| [opentranslate](https://github.com/stenastena/opentranslate/tree/3f63a6a0fe128a0b214ecab870b8d9183d52a838) | `src/main/hotkeys.ts`：`registerCaptureHotkey`；`src/main/textCapture.ts`：`captureSelectedText`；`src/main/windows/popupWindow.ts`：`showPopupWindow`、`resolveCursorAnchor`；`src/renderer/popup/popup.ts`：`onCapturedText` | 借鉴先捕获后显示、复用浮窗、每次新捕获重置状态、Esc 关闭而失焦保留、靠近鼠标并限制工作区。其直接清空/恢复文本剪贴板缺少 Workspace 的格式、owner、sequence 和焦点证明，不采用。Electron 不适合直接接入现有 WPF。 | 根 LICENSE 为 Apache-2.0；若复制需审查 NOTICE/署名/修改标记等要求。本次无源码复用。 |
| [ZTranslate](https://github.com/kzxl/ZTranslate/tree/3a93fdc39504632376ed1c7f30851c479f9287fe) | `ZeroTranslate/Services/HotkeyService.cs`：`RegisterHotkey`、`WndProc`、`Dispose`；`Services/ClipboardService.cs`：`GetSelectedTextAsync`；`Services/TextSelectionMonitor.cs`；`Views/PopupWindow.xaml.cs`：`Window_Loaded`、`Window_Deactivated`、`Window_KeyDown`；`Core/AppOrchestrator.cs` | 与 C#/WPF 最贴近：独立 hotkey ID、显式释放、光标附近浮窗和主动复制可借鉴。拒绝持续鼠标监听、无保护证明的 Copy、正文日志、吞掉恢复错误，以及失焦即关闭；保留 Workspace 的 fail-closed 捕获链。 | 根 LICENSE 为 Apache-2.0；本次仅借鉴思路，未引入其服务或 Provider。 |
| [pot-desktop](https://github.com/pot-app/pot-desktop/tree/594d32ede96acd106b0256deaa8bb440ffcdff40) | `src-tauri/src/hotkey.rs`：`register_shortcut`；`window.rs`：`selection_translate`、`text_translate`、`translate_window`；`clipboard.rs`：`start_clipboard_monitor` | 借鉴独立选区/手动入口、先取选区后更新浮窗。拒绝全局剪贴板轮询、`--disable-web-security`、未经验证的 selection helper；不移植 Tauri/云 Provider。该仓库当前 archived。 | 根 LICENSE 为 GPL v3 文本；未经单独许可评估不得复制。此次只观察交互与安全反例。 |

上述翻译应用未提供可直接套用的 Workspace 总结/解释及 Runtime 取消合同，不能把它们的翻译请求取消当成现有任务身份/取消语义的替代。

## 已有 / 可借鉴 / 必须新增

| 已有，直接复用 | 可借鉴 | X1 必须新增或修复 |
|---|---|---|
| `HotkeyRegistration` + application message HWND | 独立入口和注册失败提示 | 保留 Ctrl+Alt+Shift+T 快速翻译，新增 Ctrl+Alt+Shift+Q Quick Actions |
| `SelectionWorker`：有界 UIA 子进程、受保护输入拒绝、焦点验证 | 显示浮窗之前完成捕获 | 新捕获先清旧状态，捕获可取消，拒绝迟到结果 |
| `ControlledCopy` + `NativeCopyPort`：受控单次 Copy、owner/sequence 验证、finally 恢复 | 有界等待及异常提示 | 不增加监听、通用注入、权限或依赖 |
| `AssistantApp` / `AssistantWindow`：单一原生窗口和托盘生命周期 | 复用浮窗、Esc、工作区内定位、失焦保留 | 切换动作保留选区；关闭/取消清上下文；解释/选区提问 |
| `AssistantOperation` / `RuntimeClient`：现有 translate/summarize/ask、local-only、任务身份和取消 | 明确展示执行/取消状态，用户主动复制结果 | 继续使用原 Runtime Owner / Native Gate，不建立第二套 Runtime、队列或模型管理 |

本记录先于实施形成；实际变更、执行证据和未验收项见 [X1 交付报告](X1-CROSS-APP-REPORT.md)。
