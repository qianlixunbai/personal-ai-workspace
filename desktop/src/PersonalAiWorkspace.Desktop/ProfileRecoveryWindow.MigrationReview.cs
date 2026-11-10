#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Windows;
using System.Windows.Controls;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryWindow
{
    // Review has no migration callback, credential input or activation authority. A user's
    // acknowledgement cannot turn incomplete source/model/restart evidence into permission.
    private static void ShowMigrationReview(ProfileRecoveryCoordinator.SourceReadiness evidence)
    {
        var window=new ProfileRecoveryWindow("Personal AI Workspace · 迁移审阅",
            "尚不能执行迁移。下面是本次检查结果；构建验收记录不能代替您工作区的当前证据。\n\n"+Describe(evidence),
            "SQLite → PostgreSQL · 仅审阅当前工作区的维护条件");
        window.MaxHeight=Math.Max(400,SystemParameters.WorkArea.Height-80);
        window.Content=null;window.Content=new ScrollViewer {Content=window.body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        var choice=new ComboBox { Margin=new Thickness(0,4,0,12), SelectedIndex=0 };
        choice.Items.Add("查看 SQLite → PostgreSQL 维护迁移范围");
        choice.Items.Add("保留当前存储，不安排迁移");
        var scope=new TextBlock { TextWrapping=TextWrapping.Wrap, Margin=new Thickness(0,4,0,12) };
        void UpdateScope()=>scope.Text=choice.SelectedIndex==0
            ? "拟迁移：当前工作区的记忆、对话及知识文档。原 SQLite、知识原文和备份保留；Finance 不包含在内。\n"+
              "执行前须明确选择并重新核对目标数据库及受限应用角色。已有目标数据、后续新增数据与后来配置不能被覆盖。\n"+
              "模型选择与验证状态留在本机；模型权重、服务及旧模型任务不由数据迁移接管。\n"+
              "影响：维护期间停止常规写入；成功接管后的会话仍须安全结束。普通重启的授权流程尚未完成，当前不能作为日常工作区迁移使用。"
            : "保留当前存储。关闭此窗口不会迁移数据、发布配置或重新启动 Runtime。";
        choice.SelectionChanged+=(_,_)=>UpdateScope();UpdateScope();
        window.body.Children.Add(choice);window.body.Children.Add(scope);
        window.body.Children.Add(new TextBlock { Text="执行前还需要：完整停写证明、模型执行所有权核对、可安全重开的原生生命周期，以及对已核对来源／目标／范围的当次确认。未知结果或条件变化时必须重新审阅。",
            TextWrapping=TextWrapping.Wrap, Margin=new Thickness(0,4,0,12) });
        window.AddButton("执行迁移（安全条件未齐）",()=>{}).IsEnabled=false;
        window.close.Content="关闭审阅";window.close.IsDefault=true;
        window.ShowDialog();
    }
}
#endif
