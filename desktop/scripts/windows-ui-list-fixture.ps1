$ErrorActionPreference='Stop'
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,UIAutomationClient,UIAutomationTypes,UIAutomationProvider,System.Xaml
Add-Type -TypeDefinition @'
using System.Windows.Controls;
using System.Windows.Automation.Peers;
public class OmniTestListItem : Button {
 protected override AutomationPeer OnCreateAutomationPeer() { return new OmniTestListPeer(this); }
}
public class OmniTestListPeer : ButtonAutomationPeer {
 public OmniTestListPeer(Button owner) : base(owner) {}
 protected override AutomationControlType GetAutomationControlTypeCore() { return AutomationControlType.ListItem; }
}
'@ -ReferencedAssemblies @([System.Windows.Controls.Button].Assembly.Location,[System.Windows.Automation.Peers.AutomationPeer].Assembly.Location,[System.Windows.DependencyObject].Assembly.Location,[System.Xaml.XamlReader].Assembly.Location,[System.Windows.Automation.Provider.IInvokeProvider].Assembly.Location)
$window=New-Object System.Windows.Window
$window.Title='Omni isolated list test'; $window.Width=400; $window.Height=160; $window.ShowActivated=$false
$item=New-Object OmniTestListItem; $item.Content='Open isolated conversation'
$item.Add_Click({$window.Title='Omni isolated list opened'})
$panel=New-Object System.Windows.Controls.StackPanel
[void]$panel.Children.Add($item)
$window.Content=$panel
$window.Add_Closed({[System.Windows.Threading.Dispatcher]::CurrentDispatcher.InvokeShutdown()})
$window.Show(); [System.Windows.Threading.Dispatcher]::Run()
