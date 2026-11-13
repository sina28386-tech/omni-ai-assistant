Add-Type -AssemblyName PresentationFramework
$window = New-Object System.Windows.Window
$window.Title = 'Omni isolated UI test window'
$window.ShowActivated = $false
$window.Width = 480; $window.Height = 220
$panel = New-Object System.Windows.Controls.StackPanel
$panel.Margin = '20'
$field = New-Object System.Windows.Controls.TextBox
$field.Text = ''
[System.Windows.Automation.AutomationProperties]::SetName($field, 'Omni test input')
$button = New-Object System.Windows.Controls.Button
$button.Content = 'Omni test button'; $button.Margin = '0,16,0,0'
$button.Add_Click({
    $expected = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('2KLYstmF2KfbjNi0IE9tbmk='))
    if ($field.Text -ceq $expected) { $window.Title = 'Omni isolated UI test completed' }
})
[void]$panel.Children.Add($field); [void]$panel.Children.Add($button)
$window.Content = $panel
$window.Add_Closed({ [System.Windows.Threading.Dispatcher]::CurrentDispatcher.InvokeShutdown() })
$window.Show()
[System.Windows.Threading.Dispatcher]::Run()
