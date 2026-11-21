// SPDX-License-Identifier: GPL-3.0-only
#![cfg_attr(
    all(target_os = "windows", not(debug_assertions)),
    windows_subsystem = "windows"
)]

use anyhow::{Context, Result};
use hongguo_desktop::{library, server};

fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        #[cfg(target_os = "windows")]
        if std::env::args_os().nth(1).is_none() {
            show_start_error(&error.to_string());
        }
        std::process::exit(1);
    }
}

#[cfg(target_os = "windows")]
fn show_start_error(message: &str) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MB_ICONERROR, MB_OK, MessageBoxW};

    let title: Vec<u16> = "红果短剧".encode_utf16().chain([0]).collect();
    let message: Vec<u16> = message.encode_utf16().chain([0]).collect();
    // Both strings are terminated and stay alive until the dialog closes.
    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            message.as_ptr(),
            title.as_ptr(),
            MB_ICONERROR | MB_OK,
        );
    }
}

fn run() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if matches!(args.first().map(String::as_str), Some("--version" | "-V")) {
        println!("hongguo-desktop {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    if matches!(args.first().map(String::as_str), Some("--help" | "-h")) {
        println!("hongguo-desktop [--serve | --version]\n--serve  仅启动本机服务，用于开发和验证");
        return Ok(());
    }
    let serve = args.as_slice() == ["--serve"];
    anyhow::ensure!(serve || args.is_empty(), "未知参数，请使用 --help");
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("无法启动异步运行时")?;
    let server = runtime.block_on(server::start(&library::data_directory()?))?;
    if serve {
        println!("{}", server.url);
        runtime
            .block_on(tokio::signal::ctrl_c())
            .context("等待退出信号失败")?;
    } else {
        desktop(runtime, server)?;
    }
    Ok(())
}

#[cfg(all(feature = "desktop", any(target_os = "windows", target_os = "macos")))]
fn desktop(runtime: tokio::runtime::Runtime, server: server::RunningServer) -> Result<()> {
    use tao::{
        dpi::LogicalSize,
        event::{Event, WindowEvent},
        event_loop::{ControlFlow, EventLoop},
        window::{Theme, WindowBuilder},
    };
    use wry::{NewWindowResponse, WebViewBuilder};

    let event_loop = EventLoop::new();
    let window = WindowBuilder::new()
        .with_title("hongguoTV")
        .with_theme(Some(Theme::Dark))
        .with_background_color((17, 19, 23, 255))
        .with_inner_size(LogicalSize::new(1180.0, 800.0))
        .with_min_inner_size(LogicalSize::new(700.0, 525.0))
        .build(&event_loop)
        .context("无法创建红果窗口")?;
    let scope = server
        .url
        .strip_suffix("index.html")
        .unwrap_or(&server.url)
        .to_owned();
    let webview = WebViewBuilder::new()
        .with_background_color((17, 19, 23, 255))
        .with_url(&server.url)
        .with_navigation_handler(move |url| url.starts_with(&scope))
        .with_new_window_req_handler(|url, _| {
            if hongguo_desktop::catalog::is_official_player_url(&url) {
                std::thread::spawn(move || open_official_episode(&url));
            }
            NewWindowResponse::Deny
        })
        .build(&window)
        .context("无法启动系统 WebView")?;
    event_loop.run(move |event, _, flow| {
        let _keep_alive = (&runtime, &server, &window, &webview);
        *flow = ControlFlow::Wait;
        if let Event::WindowEvent {
            event: WindowEvent::CloseRequested,
            ..
        } = event
        {
            *flow = ControlFlow::Exit;
        }
    })
}

#[cfg(all(feature = "desktop", any(target_os = "macos", target_os = "windows")))]
fn open_official_episode(url: &str) {
    #[cfg(target_os = "macos")]
    let command = "/usr/bin/open";
    #[cfg(target_os = "windows")]
    let command = "explorer.exe";
    if std::process::Command::new(command)
        .arg(url)
        .status()
        .is_err()
    {
        eprintln!("无法打开官网播放页");
    }
}

#[cfg(not(all(feature = "desktop", any(target_os = "windows", target_os = "macos"))))]
fn desktop(_runtime: tokio::runtime::Runtime, _server: server::RunningServer) -> Result<()> {
    anyhow::bail!("当前构建仅支持 --serve，桌面窗口需要 macOS 或 Windows")
}
