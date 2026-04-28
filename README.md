# 智能开关控制系统

## 架构
```
[开关设备] --TCP 9000--> [服务端 192.168.76.121] <--HTTP 8080-- [安卓APP]
```

## 服务端部署

```bash
cd server/
python3 switch_server.py
```
- TCP 9000: 接收开关设备连接
- HTTP 8080: 提供API给安卓APP

## HTTP API

### GET /api/devices
获取所有设备状态（含在线状态和开关状态）

### GET /api/config
获取设备配置

### POST /api/control
控制单路开关
```json
{"ip":"192.168.39.20", "address":1, "channel":3, "action":"on"}
```

### POST /api/control_all
全开/全关
```json
{"ip":"192.168.39.20", "address":1, "number":8, "action":"off"}
```

## 安卓APP

### 编译
1. 用 Android Studio 创建新项目 `com.smartswitch.app`
2. 替换 `MainActivity.java`、`AndroidManifest.xml`、`build.gradle`
3. 无需第三方依赖，纯原生实现
4. 编译运行即可

### 功能
- 自动从服务端加载设备列表
- 每3秒轮询刷新状态
- 单路开/关控制
- 模块级全开/全关
- 在线/离线状态显示
- 💡/⚫ 直观显示开关状态

## 通信协议 (Modbus RTU over TCP)
- 功能码: 0x10 (写多寄存器)
- 控制寄存器: 0x001F
- 状态上报寄存器: 0x0004
- 通道掩码: 高12位有效, 低4位固定0x8
- 企业代码: 0x00C8
- CRC: Modbus CRC16
