; SPDX-License-Identifier: GPL-3.0-only
Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"

!define APP_NAME "hongguoTV"
!define APP_EXE "Hongguo Rust.exe"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\HongguoTV.Rust"
!define WEBVIEW_KEY "Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

Name "${APP_NAME}"
OutFile "${SETUP_NAME}"
InstallDir "$LOCALAPPDATA\Programs\hongguoTV"
RequestExecutionLevel user
SetCompressor /SOLID lzma
VIProductVersion "${FILE_VERSION}"
VIAddVersionKey /LANG=2052 "ProductName" "${APP_NAME}"
VIAddVersionKey /LANG=2052 "FileDescription" "hongguoTV 安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=2052 "LegalCopyright" "hongguoTV contributors"

!define MUI_ABORTWARNING
!define MUI_FINISH_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISH_RUN_TEXT "启动 hongguoTV"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Function .onInit
    ${IfNot} ${RunningX64}
        MessageBox MB_ICONSTOP|MB_OK "此安装包需要 64 位 Windows。"
        SetErrorLevel 1
        Abort
    ${EndIf}
    SetRegView 64
    SetShellVarContext current
    ReadRegStr $0 HKCU "${UNINSTALL_KEY}" "InstallLocation"
    ${If} $0 != ""
        StrCpy $INSTDIR $0
    ${EndIf}
FunctionEnd

Function ReadWebView
    SetRegView 32
    ReadRegStr $0 HKLM "${WEBVIEW_KEY}" "pv"
    ${If} $0 == ""
    ${OrIf} $0 == "0.0.0.0"
        ReadRegStr $0 HKCU "${WEBVIEW_KEY}" "pv"
    ${EndIf}
    ${If} $0 == "0.0.0.0"
        StrCpy $0 ""
    ${EndIf}
    SetRegView 64
FunctionEnd

Section "hongguoTV"
    Call ReadWebView
    ${If} $0 == ""
        InitPluginsDir
        SetOutPath "$PLUGINSDIR"
        File "MicrosoftEdgeWebview2Setup.exe"
        DetailPrint "正在安装 Microsoft Edge WebView2 运行时..."
        ExecWait '"$PLUGINSDIR\MicrosoftEdgeWebview2Setup.exe" /silent /install'
        Call ReadWebView
        ${If} $0 == ""
            MessageBox MB_ICONSTOP|MB_OK "WebView2 运行时安装失败。请检查网络连接后重新运行安装程序。"
            SetErrorLevel 1
            Abort
        ${EndIf}
    ${EndIf}

    SetOutPath "$INSTDIR"
    ClearErrors
    File "/oname=${APP_EXE}" "app.exe"
    IfErrors install_failed
    !ifdef INCLUDE_LOADER
        File "WebView2Loader.dll"
        IfErrors install_failed
    !endif
    File "LICENSE.txt"
    File "NOTICE.txt"
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    CreateDirectory "$SMPROGRAMS\hongguoTV"
    CreateShortCut "$SMPROGRAMS\hongguoTV\hongguoTV.lnk" "$INSTDIR\${APP_EXE}"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "${APP_NAME}"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\${APP_EXE}"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
    Goto done

    install_failed:
        MessageBox MB_ICONSTOP|MB_OK "无法写入程序文件。请关闭正在运行的 hongguoTV 后重试。"
        SetErrorLevel 1
        Abort
    done:
SectionEnd

Section "Uninstall"
    SetRegView 64
    SetShellVarContext current
    ClearErrors
    Delete "$INSTDIR\${APP_EXE}"
    ${If} ${Errors}
        MessageBox MB_ICONSTOP|MB_OK "请关闭 hongguoTV 后重新卸载。"
        SetErrorLevel 1
        Abort
    ${EndIf}
    Delete "$INSTDIR\WebView2Loader.dll"
    Delete "$INSTDIR\LICENSE.txt"
    Delete "$INSTDIR\NOTICE.txt"
    Delete "$INSTDIR\Uninstall.exe"
    Delete "$SMPROGRAMS\hongguoTV\hongguoTV.lnk"
    RMDir "$SMPROGRAMS\hongguoTV"
    RMDir "$INSTDIR"
    DeleteRegKey HKCU "${UNINSTALL_KEY}"
SectionEnd
