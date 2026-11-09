using System;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Automation;

namespace PersonalAiWorkspace.Desktop;

internal sealed class KnowledgeDocxPreview:Window
{
    internal KnowledgeDocxPreview(Window owner,KnowledgeDocx.Excerpt excerpt)
    {
        Owner=owner;Title="确认导入 Word 正文摘录";Width=840;Height=660;MinWidth=560;MinHeight=400;
        WindowStartupLocation=WindowStartupLocation.CenterOwner;
        var panel=new DockPanel{Margin=new Thickness(18)};
        var notice=new TextBlock{Text=KnowledgeDocx.Notice,TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,0,0,12)};
        DockPanel.SetDock(notice,Dock.Top);panel.Children.Add(notice);
        var buttons=new StackPanel{Orientation=Orientation.Horizontal,HorizontalAlignment=HorizontalAlignment.Right,Margin=new Thickness(0,12,0,0)};
        var confirm=new Button{Content="确认导入这份摘录",Padding=new Thickness(16,8,16,8),Margin=new Thickness(8,0,0,0)};
        var cancel=new Button{Content="取消",IsCancel=true,Padding=new Thickness(16,8,16,8)};
        AutomationProperties.SetAutomationId(confirm,"ConfirmWordExcerpt");
        confirm.Click+=(_,_)=>DialogResult=true;buttons.Children.Add(cancel);buttons.Children.Add(confirm);
        DockPanel.SetDock(buttons,Dock.Bottom);panel.Children.Add(buttons);
        var text=new TextBox{Text=excerpt.Text,IsReadOnly=true,AcceptsReturn=true,AcceptsTab=true,TextWrapping=TextWrapping.Wrap,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Auto};
        AutomationProperties.SetName(text,"Word 正文摘录预览");panel.Children.Add(text);Content=panel;
    }
    internal static bool Confirm(Window owner,KnowledgeDocx.Excerpt excerpt,CancellationToken token)
    {
        token.ThrowIfCancellationRequested();var preview=new KnowledgeDocxPreview(owner,excerpt);
        using var stop=token.Register(()=>preview.Dispatcher.BeginInvoke(new Action(()=>{if(preview.IsVisible)preview.Close();})));
        bool accepted=preview.ShowDialog()==true;token.ThrowIfCancellationRequested();return accepted;
    }
}
