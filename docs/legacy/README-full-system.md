# MASTER EXECUTION PROMPT — AUTONOMOUS DOWNLOAD SYSTEM

## 1. Vai trò và nhiệm vụ

Bạn là Principal Software Architect, Embedded Systems Engineer, Backend Engineer, Windows Service Engineer, Android Engineer, Security Engineer và QA/DevOps Engineer.

Nhiệm vụ của bạn là **TRỰC TIẾP TRIỂN KHAI, CHẠY, KIỂM THỬ, SỬA LỖI VÀ HOÀN THIỆN** dự án Autonomous Download System thành một hệ thống có thể cài đặt, kết nối và vận hành thực tế.

Đây không phải yêu cầu viết tài liệu, tạo mockup, tạo skeleton hoặc chỉ đề xuất kiến trúc. Bạn phải làm việc trực tiếp trên filesystem và repository của môi trường đang chạy agent, sử dụng terminal, build tools, test runners, debugger và các công cụ sẵn có.

### Quy tắc thực thi bắt buộc

1. Không hỏi lại người dùng về lựa chọn kỹ thuật, thông số mặc định, cấu trúc dự án hoặc các chi tiết có thể tự quyết định bằng chuyên môn.
2. Không dừng công việc chỉ vì thiếu API key, token, địa chỉ IP, thông tin mạng, thư mục lưu file, mật khẩu hoặc cấu hình bên ngoài.
3. Mọi thông tin có thể thay đổi sau khi cài đặt phải có cơ chế cấu hình, kiểm tra hợp lệ, lưu trữ bền vững và chỉnh sửa qua giao diện người dùng phù hợp.
4. Khi thiếu thông tin hoặc dịch vụ bên ngoài, triển khai đầy đủ phần tích hợp, giao diện cấu hình, trạng thái chưa thiết lập, kiểm tra kết nối và hướng dẫn kích hoạt. Tiếp tục thực hiện mọi chức năng không phụ thuộc vào thông tin đó.
5. Không được thay thế chức năng thực bằng dữ liệu giả, nút bấm không hoạt động, mock API hoặc giao diện trình diễn rồi tuyên bố hoàn thành.
6. Mọi chức năng được đánh dấu hoàn thành phải có mã triển khai và bằng chứng kiểm thử phù hợp.
7. Sau khi gặp lỗi build hoặc test, phải phân tích nguyên nhân, sửa mã và chạy lại. Không chỉ ghi lỗi vào báo cáo.
8. Không kết thúc chỉ sau khi lập kế hoạch. Hãy thực hiện liên tục cho đến khi hoàn thành tối đa những gì môi trường cho phép.
9. Không tuyên bố đã build, cài đặt, chạy hoặc kiểm thử phần cứng nếu chưa thực sự thực hiện.
10. Không xóa hoặc ghi đè dữ liệu hiện có của người dùng một cách không an toàn. Trước khi thay đổi repository hiện hữu, kiểm tra trạng thái, giữ nguyên công việc có sẵn và bảo vệ dữ liệu.
11. Không yêu cầu người dùng nhập thông tin nhạy cảm trực tiếp vào prompt, log, source code hoặc Git.
12. Chỉ yêu cầu thao tác thủ công ở bước cuối khi thực sự cần quyền truy cập vật lý, tài khoản bên ngoài hoặc thiết bị mà agent không thể điều khiển. Phải cung cấp hướng dẫn cụ thể và tiếp tục hoàn thành mọi phần còn lại.

## 2. Mục tiêu sản phẩm

Xây dựng một hệ thống tải xuống đa nền tảng, hoạt động chủ yếu trong mạng nội bộ, với ESP32-CYD là bộ điều khiển trung tâm.

Hệ thống gồm bốn thành phần chính:

- ESP32-CYD firmware: bộ điều khiển trung tâm, màn hình cảm ứng, hàng đợi, cấu hình, lưu trữ, API và Telegram.
- Windows Runtime: Windows Service thực thi tải xuống bằng yt-dlp, aria2c khi phù hợp và FFmpeg.
- Android App: ứng dụng native để kết nối, cấu hình, theo dõi và quản lý toàn bộ hệ thống.
- Web UI: giao diện quản trị qua trình duyệt, sử dụng cùng API với Android và màn hình CYD.

Tất cả các giao diện phải điều khiển cùng một hệ thống. Không được tạo ba hệ thống cấu hình, hàng đợi hoặc trạng thái độc lập.

**ESP32 là nguồn dữ liệu chuẩn cho chính sách tải xuống, cấu hình chung và trạng thái hàng đợi. Windows là nguồn dữ liệu thực tế cho tiến trình thực thi và kết quả tải file.**

Không sử dụng Tailscale. Không yêu cầu cloud backend. Không phụ thuộc dịch vụ cloud riêng để các thành phần hoạt động trong LAN.

## 3. Phần cứng mục tiêu

Phần cứng mục tiêu:

- Board: ESP32-CYD-3248S035R đã được mod phần cứng.
- Cấu hình nền: ESP32 Dev Module (`esp32dev`), tương thích profile ESP32-WROOM-32 của CYD.
- Module lắp trên board mod: ESP32-WROVER N16R8.
- Cổng kết nối: COM 6.
- Flash cấu hình: 16 MB (ghi đè dung lượng mặc định 4 MB của profile nền).
- PSRAM: driver nhận dung lượng chip 8 MB; firmware dùng vùng ánh xạ trực tiếp 4 MB theo giới hạn ESP32 classic.
- TFT: 320 × 480.
- Giao diện: LVGL.
- Mạng: Wi-Fi LAN.
- Hỗ trợ microSD nếu phần cứng thực tế có kết nối và driver phù hợp.

Không được mặc định GPIO, mạch SD, touch controller, đèn nền hoặc đường anten của mọi biến thể CYD giống nhau.

Ngay khi bắt đầu, kiểm tra mã nguồn hiện hữu và cấu hình board. Triển khai báo cáo chẩn đoán phần cứng khi khởi động, bao gồm dung lượng Flash/PSRAM thực tế, heap khả dụng, trạng thái Wi-Fi, filesystem và lỗi ngoại vi.

Nếu không có thiết bị vật lý, tạo cấu hình board có thể điều chỉnh, chạy kiểm thử host-side và build firmware nếu toolchain có sẵn. Ghi rõ những mục chưa thể xác minh trên thiết bị thật.

## 4. Kiến trúc repository

Trước tiên, kiểm tra toàn bộ repository hiện có và các tài liệu được cung cấp. Nếu đã có bộ khung AutonomousDownloadSystem, phải tiếp tục hoàn thiện bộ khung đó thay vì tạo một dự án thứ hai hoặc đổi tên để né các phần chưa làm.
Nếu chưa có bộ khung thì bắt đầu xây dựng mới.

Tổ chức repository thống nhất theo cấu trúc tương đương:

```text
AutonomousDownloadSystem/
├── README.md
├── shared/
│   ├── api-contract/
│   ├── config-schema/
│   ├── job-model/
│   └── protocol/
├── firmware-cyd/
│   ├── main/
│   ├── components/
│   ├── storage/
│   ├── queue/
│   ├── api/
│   ├── worker-manager/
│   ├── telegram/
│   ├── network/
│   ├── ui-lvgl/
│   └── tests/
├── windows-runtime/
│   ├── service/
│   ├── downloader/
│   ├── progress-reporter/
│   ├── recovery/
│   ├── installer/
│   └── tests/
├── android-app/
│   ├── app/
│   └── tests/
├── web-ui/
├── tools/
├── scripts/
├── tests/
└── docs/
```

Có thể điều chỉnh chi tiết để phù hợp với repository và toolchain, nhưng phải giữ một kiến trúc thống nhất, một hợp đồng API có phiên bản và một quy trình build rõ ràng.

Ưu tiên các lựa chọn:

- ESP32: ESP-IDF với driver/display stack tương thích phần cứng hiện có; giữ lại LVGL nếu đã được tích hợp.
- Windows: .NET Worker Service chạy dưới dạng Windows Service.
- Android: Kotlin + Jetpack Compose, ứng dụng native.
- Web UI: frontend đẹp, hiện đại, vận hành mượt, tối ưu tài nguyên và có thể được phục vụ từ ESP32.
- Hợp đồng: OpenAPI và JSON Schema có phiên bản, được kiểm tra tự động.

Không viết lại một module đang hoạt động tốt chỉ để thay đổi công nghệ. Ưu tiên tính ổn định và khả năng tích hợp.

## 5. Yêu cầu bắt buộc: mọi dữ liệu cấu hình phải nhập được từ giao diện

Đây là yêu cầu quan trọng nhất.

**Không được để một thông tin cần thiết chỉ tồn tại dưới dạng hằng số trong mã nguồn, biến môi trường bắt buộc, file JSON phải tự sửa bằng tay hoặc tham số dòng lệnh mà người dùng không có giao diện cấu hình.**

Mọi thông tin có thể thiếu khi cài đặt phải có:

1. Trường nhập hoặc lựa chọn phù hợp.
2. Giá trị hiện tại hoặc trạng thái chưa thiết lập.
3. Kiểm tra tính hợp lệ.
4. Nút lưu và thông báo kết quả lưu.
5. Lưu trữ bền vững qua khởi động lại.
6. Khả năng chỉnh sửa sau khi cài đặt.
7. Nút kiểm tra kết nối hoặc kiểm tra cấu hình nếu áp dụng.
8. Thông báo lỗi dễ hiểu và hướng xử lý.
9. Cơ chế che dữ liệu nhạy cảm.
10. Trạng thái đồng bộ giữa các giao diện.

Các cấu hình chung được lưu tại ESP32 và được cung cấp thông qua API. Cấu hình riêng của Windows, Android hoặc từng thiết bị có thể được lưu cục bộ khi cần, nhưng phải phân biệt rõ với cấu hình chung.

### 5.1. ESP32 LVGL 320x480.

Thiết kế các trang:

- Dashboard.
- Queue.
- Job detail.
- Network.
- Windows Worker.
- Telegram.
- Download Settings.
- Storage.
- Security.
- System Diagnostics.
- Logs.
- Setup Wizard.

Tất cả các thiết lập thiết yếu phải thao tác được trên màn hình cảm ứng. Vì màn hình 320 × 480 hạn chế không gian, sử dụng menu phân trang, bàn phím cảm ứng và biểu mẫu gọn gàng.

Không cần hiển thị mọi trường trên một trang. Tuy nhiên, không được bỏ tính năng cấu hình chỉ vì màn hình nhỏ.

### 5.2. Web UI

Web UI phải có đầy đủ chức năng quản trị tương đương với Android đối với các tính năng được hỗ trợ:

- Nhập và lưu cấu hình.
- Thiết lập Telegram.
- Thiết lập kết nối worker.
- Quản lý hàng đợi và lịch sử.
- Theo dõi tiến trình.
- Chỉnh chính sách tải xuống.
- Quản lý storage.
- Quản lý xác thực và bảo mật.
- Xem log, chẩn đoán và tình trạng hệ thống.
- Backup, Khôi phục hoặc thay đổi cấu hình.

### 5.3. Android App

Android phải là ứng dụng native, không chỉ là WebView đóng gói.

Có các màn hình:

- Discovery / Pairing Wifi/Bluetooth.
- Dashboard.
- Queue.
- Job detail.
- Settings.
- Telegram setup.
- Windows Worker setup.
- Storage.
- Logs / Diagnostics.
- Security / Device management.

Tất cả các trường cấu hình chung phải đọc và cập nhật thông qua API của ESP32. Sau khi lưu, trạng thái hiển thị trên CYD, web và Android phải nhất quán.

## 6. Các cấu hình phải triển khai

Tạo một hệ thống cấu hình có schema, kiểu dữ liệu, giá trị mặc định, validation, phân loại bí mật và phiên bản.

### 6.1. Thiết lập mạng

- khi khởi động, Nếu chưa có wifi sẽ phát một AP (SSID: espsystem/ passd: espsystem) để thiết bị android connect vào local host và nhập các thông tin cài đặt.
- Wi-Fi SSID.
- Wi-Fi password.
- Chế độ DHCP hoặc địa chỉ tĩnh nếu cần.
- Hostname/mDNS.
- API port.
- Trạng thái kết nối.
- Địa chỉ IP hiện tại.
- Cấu hình reconnect.
- Kiểm tra DNS và Internet khi cần.
- Chế độ provisioning hoặc recovery nếu Wi-Fi không kết nối được.
- Bluetooth là lựa chọn dự phòng.

Không hiển thị lại password dưới dạng plaintext sau khi lưu. Phải có cơ chế thay đổi password và khôi phục kết nối an toàn.

### 6.2. Telegram

Có trang Telegram Settings với:

- Enable/disable bot.
- Bot Token.
- Allowed Telegram user ID/chat ID.
- Danh sách người dùng được phép.
- Kiểm tra token và kết nối.
- Lấy thông tin bot.
- Trạng thái polling/webhook phù hợp với kiến trúc.
- Thời điểm cập nhật gần nhất.
- Lỗi gần nhất.
- Nút lưu, kiểm tra và bật/tắt.
- Hướng dẫn lấy token và ID ngay trong giao diện.
- Cơ chế thay đổi hoặc xóa token.
- Xử lý trùng Telegram update.
- Giới hạn người được phép gửi URL và lệnh quản trị.

Không yêu cầu token có sẵn để firmware build hoặc để các chức năng không liên quan hoạt động.

Khi chưa cấu hình Telegram, hiển thị `NOT_CONFIGURED`, cho phép nhập token sau này và tiếp tục sử dụng API, Windows worker, Android, web và hàng đợi.

Không ghi token vào log hoặc trả token đầy đủ qua API đọc cấu hình.

### 6.3. Windows Worker

Có trang Worker Settings và quy trình pairing.

Các trường cần thiết:

- Tên worker.
- Địa chỉ endpoint của ESP32.
- Trạng thái pairing.
- Worker ID.
- Worker credential.
- Phiên bản runtime.
- Phiên bản yt-dlp.
- Phiên bản FFmpeg.
- Phiên bản aria2c.
- Output root.
- Đường dẫn executable khi không thể tự phát hiện.
- Trạng thái ổ đĩa.
- Dung lượng trống.
- Số job đang chạy.
- Thời điểm heartbeat gần nhất.
- Lỗi gần nhất.
- Khả năng hỗ trợ từng loại tác vụ.

Worker chủ động kết nối/poll API của ESP32 để tránh yêu cầu ESP32 phải truy cập trực tiếp máy Windows qua firewall.

Địa chỉ endpoint và thông tin pairing phải có thể thay đổi từ giao diện. Dùng cơ chế enrollment có xác thực, không để một worker bất kỳ tự đăng ký.

### 6.4. Download Settings

Cho phép cấu hình:

- Video quality: Best, tối đa 1080p, tối đa 720p, audio-only.
- Image quality.
- Output root.
- Cấu trúc thư mục đầu ra.
- Quy tắc đặt tên file.
- Định dạng đầu ra.
- Tự merge audio/video khi cần.
- Số job chạy đồng thời.
- Số kết nối phân đoạn.
- Giới hạn băng thông.
- Retry limit.
- Retry delay/backoff.
- Xử lý URL trùng.
- Chính sách khi file đã tồn tại.
- Timeout.
- Chính sách xác minh đầu ra.
- Adaptive mode.
- Giới hạn tài nguyên.
- Giới hạn dung lượng lưu trữ nếu áp dụng.

Phân biệt rõ concurrency giữa các job và số kết nối phân đoạn trong một job.

Không mặc định aria2c luôn tăng tốc. Chỉ sử dụng trong workflow tương thích. yt-dlp được ưu tiên trước hết, đảm nhiệm trích xuất và lựa chọn định dạng; FFmpeg được dùng khi cần merge hoặc xử lý định dạng.

### 6.5. Storage Settings

Cho phép:

- AUTO.
- LittleFS.
- microSD nếu được phát hiện và hỗ trợ.
- Chính sách lịch sử.
- Chính sách log rotation.
- Giới hạn dung lượng log.
- Thời gian lưu lịch sử.
- Kiểm tra filesystem.
- Hiển thị tổng dung lượng, dung lượng còn lại và trạng thái lỗi.
- Xuất log/chẩn đoán nếu hỗ trợ.
- Thông báo khi storage lỗi hoặc gần đầy.

Không được âm thầm xóa job để giải phóng dung lượng.

### 6.6. Security Settings

- Khởi tạo mật khẩu quản trị lần đầu.
- Thay đổi mật khẩu.
- Quản lý session.
- Pair/unpair worker.
- Thay đổi hoặc thu hồi credential.
- Quản lý người dùng Telegram được phép.
- Timeout session.
- Giới hạn đăng nhập.
- Trạng thái bảo mật.
- Khôi phục quyền truy cập theo quy trình an toàn.

Không tạo mật khẩu mặc định cố định được dùng chung cho mọi thiết bị.

## 7. Lưu trữ và cấu hình bền vững

Thiết kế storage abstraction cho ESP32.

Ưu tiên:

- NVS cho cấu hình nhỏ và thông tin ít thay đổi.
- LittleFS cho cấu hình có cấu trúc, journal hàng đợi và log ngắn hạn.
- microSD tùy chọn cho lịch sử/log dài hạn nếu phần cứng hỗ trợ.

Yêu cầu:

- Cấu hình không mất khi khởi động lại.
- Queue không chỉ tồn tại trong RAM.
- Ghi cấu hình theo kiểu atomic hoặc cơ chế an toàn tương đương.
- Có schema version và migration.
- Phát hiện file hỏng.
- Có backup/restore hợp lý.
- Xử lý filesystem đầy.
- Xử lý thẻ nhớ bị tháo hoặc lỗi.
- Không làm hỏng toàn bộ cấu hình khi một trường sai.
- Không báo lưu thành công trước khi ghi bền vững thành công.
- Khi không còn storage bền vững khả dụng, từ chối nhận job mới với lỗi rõ ràng.

Nếu người dùng chọn AUTO, tự chọn backend khả dụng theo policy đã định nghĩa, hiển thị backend đang dùng và lý do lựa chọn.

Không để việc thiếu thẻ SD chặn firmware nếu LittleFS vẫn đủ điều kiện hoạt động.

## 8. Hàng đợi và vòng đời job

Triển khai một state machine rõ ràng:

`RECEIVED → QUEUED → DISPATCHED → DOWNLOADING → VERIFYING → COMPLETED`

Các trạng thái bổ sung:

- `RETRY_WAIT`
- `FAILED`
- `PAUSED`
- `CANCEL_REQUESTED`
- `CANCELLED`
- `RECOVERY_REQUIRED`

Mỗi job cần có:

- Job ID duy nhất.
- URL.
- Nguồn: Telegram, Android, web, CYD hoặc API.
- Thời điểm tạo.
- Trạng thái.
- Attempt ID và số lần thử.
- Tiến trình phần trăm.
- Tốc độ tải.
- ETA.
- Tên file.
- Đường dẫn đầu ra.
- Lỗi cụ thể và mã lỗi.
- Worker thực thi.
- Cấu hình snapshot của job.
- Lịch sử sự kiện.
- Liên kết tới job gốc nếu re-download.
- Lịch sử url phải được lưu lại trong url.log

Các thao tác bắt buộc:

- Add URL.
- List jobs.
- Job detail.
- Cancel.
- Retry.
- Re-download.
- Force download (ưu tiên chạy ngay lập tức và trước tất cả các tiến trình khác)
- Delete job/history.
- Xóa file đầu ra chỉ khi người dùng chủ động yêu cầu và có xác nhận 2 lớp phù hợp.
- Filter/sort/pagination.
- Theo dõi tiến trình gần thời gian thực.
- Thông tin về file đã tải (độ phân giải, dung lượng file, thời gian tải)

Phân biệt xóa lịch sử với xóa file.

Re-download phải tạo job mới, không giả vờ là job cũ chưa từng chạy.

Không báo `QUEUED` trước khi lưu bền vững thành công.

Nếu worker biến mất, lease hết hạn hoặc ESP32 khởi động lại, phải đối soát trạng thái để tránh báo thành công giả hoặc tạo nhiều lượt tải trùng ngoài ý muốn.

Mọi retry phải có giới hạn, backoff và trạng thái lỗi rõ ràng.

## 9. Windows Service

Triển khai Windows Service thực sự, tự khởi động khi Windows boot và chạy trước khi người dùng đăng nhập.

Không phụ thuộc vào cửa sổ terminal, PowerShell tương tác hoặc biến môi trường chỉ tồn tại trong user session.

Các yêu cầu:

- Dùng .NET Worker Service hoặc công nghệ tương đương có thể cài như Windows Service.
- Cung cấp script cài đặt, gỡ cài đặt, nâng cấp và kiểm tra trạng thái service.
- Có logging và chẩn đoán.
- Có heartbeat.
- Có shutdown/cancellation an toàn.
- Có xử lý mất mạng và reconnect.
- Có recovery sau crash/restart.
- Kiểm tra ổ đĩa và dung lượng trống.
- Dùng đường dẫn executable tuyệt đối.
- Không dựa vào mapped drive không có trong service session.
- Kiểm tra quyền đọc/ghi của service account.
- Không yêu cầu Windows đăng nhập để thực hiện tải xuống.
- Không cố bypass màn hình đăng nhập, PIN hoặc chính sách bảo mật Windows.

Service chỉ thực thi job và gửi sự kiện về ESP32. Nó không trở thành nơi quản lý cấu hình chung hoặc nguồn dữ liệu chuẩn của queue.

### Downloader engine

- yt-dlp: trích xuất thông tin và tải nội dung được hỗ trợ.
- FFmpeg: merge hoặc xử lý khi cần.
- aria2c: tùy chọn cho workflow tương thích.
- Tool discovery: tự tìm đường dẫn phổ biến, kiểm tra phiên bản và cho phép người dùng cấu hình đường dẫn thay thế.
- Output path: cho phép chỉnh qua giao diện quản trị.
- Progress parser: sử dụng output có cấu trúc khi có thể.
- Error handling: phân loại lỗi mạng, lỗi xác thực, lỗi định dạng, lỗi công cụ, hết dung lượng và lỗi ghi file.
- Output verification: xác minh exit code, file đầu ra và metadata cần thiết trước khi báo hoàn thành.

Không ghép URL hoặc dữ liệu người dùng vào chuỗi shell tùy tiện. Sử dụng API truyền argument an toàn của process runner.

Không cho phép API truyền lệnh shell, PowerShell hoặc đường dẫn executable tùy ý để thực thi từ xa.

Nếu tool chưa được cài, service phải báo `TOOL_NOT_CONFIGURED`, hiển thị hướng dẫn cài đặt/cấu hình và tiếp tục hoạt động ở các phần không phụ thuộc công cụ đó.

## 10. API và giao tiếp

Thiết kế API có phiên bản tại `/api/v1.0`.

Các nhóm endpoint tối thiểu:

- System health/capabilities.
- Jobs.
- Job attempts/history.
- Settings.
- Storage.
- Network.
- Workers.
- Authentication/session.
- Events.
- Diagnostics.
- Worker pairing, heartbeat, claim và result.

Ví dụ:

- `GET /api/v1/system`
- `GET /api/v1/jobs`
- `POST /api/v1/jobs`
- `GET /api/v1/jobs/{id}`
- `POST /api/v1/jobs/{id}/retry`
- `POST /api/v1/jobs/{id}/redownload`
- `POST /api/v1/jobs/{id}/cancel`
- `DELETE /api/v1/jobs/{id}`
- `GET /api/v1/settings`
- `PUT /api/v1/settings`
- `GET /api/v1/storage`
- `GET /api/v1/workers`
- `POST /api/v1/worker/v1/hello`
- `POST /api/v1/worker/v1/heartbeat`
- `POST /api/v1/worker/v1/claim`
- `POST /api/v1/worker/v1/jobs/{id}/events`
- `POST /api/v1/worker/v1/jobs/{id}/result`

Hoàn thiện hợp đồng API trước khi triển khai tất cả client.

Yêu cầu:

- JSON UTF-8.
- Timestamp chuẩn.
- Schema validation.
- Error code ổn định.
- Request ID.
- Idempotency key.
- Pagination.
- Giới hạn request body.
- Rate limiting ở các endpoint phù hợp.
- Authentication và authorization.
- Worker lease và attempt ID.
- Event sequence để bỏ qua sự kiện trùng hoặc cũ.
- WebSocket hoặc cơ chế streaming sự kiện phù hợp, có reconnect và fallback polling.

Nếu endpoint hoặc phương thức nào không phù hợp với giới hạn tài nguyên của ESP32, chọn phương án đơn giản và ổn định hơn, nhưng phải cập nhật đồng nhất hợp đồng, firmware, Windows, Android và web.

## 11. Android LAN discovery

Triển khai ứng dụng native có thể tìm và kết nối tới CYD trong LAN.

Ưu tiên:

1. Địa chỉ thiết bị đã lưu.
2. mDNS, ví dụ `download-gateway.local`.
3. LAN discovery phù hợp với môi trường Android.
4. Nhập IP/hostname thủ công.
5. Nhập SSID/Passd từ android.

Không giả định mDNS hoặc broadcast luôn hoạt động trên mọi router.

Sau khi tìm thấy endpoint, kiểm tra `/api/v1/system`, phiên bản API và trạng thái thiết bị trước khi kết nối.

Cho phép người dùng lưu nhiều profile thiết bị nếu phù hợp.

Ứng dụng phải hiển thị rõ:

- Đã kết nối.
- Mất kết nối.
- Loại kết nối (Wifi/Bluetooth)
- Địa chỉ MAC
- Địa chỉ IP
- Host name/ Local Name.
- Đang tìm kiếm.
- Sai phiên bản API.
- Chưa xác thực.
- Không tìm thấy thiết bị.
- Thiết bị đang khởi động.
- Worker offline.
- Cấu hình còn thiếu.

Không tuyên bố kết nối từ mạng di động hoặc Internet nếu chưa có cơ chế truy cập từ xa riêng. Mặc định hệ thống chỉ phục vụ LAN hoặc mạng nội bộ có định tuyến phù hợp.

## 12. Web UI và giao diện CYD

Giao diện phải là sản phẩm có thể sử dụng, không phải ảnh mockup.

### Dashboard

Hiển thị:

- Hardware info.
- ESP32 online/offline.
- Windows Worker online/offline.
- CPU/Spram/Ram load.(mini circle/bar/text number)
- Queue count.
- Active jobs.
- Completed/failed jobs.
- Throughput tổng.
- Storage health.
- Wi-Fi signal/status.
- Telegram status.
- Lỗi cần xử lý.

### Queue

Hiển thị URL, trạng thái, tiến trình, tốc độ, ETA, nguồn, tên file, worker và thao tác có thể thực hiện.

Không cập nhật tiến trình giả. Dữ liệu tiến trình phải bắt nguồn từ Windows worker hoặc trạng thái thực được ghi nhận.

### Settings

Tổ chức theo nhóm rõ ràng, có validation, save/cancel, trạng thái dirty, thông báo lưu thành công/thất bại và khả năng khôi phục giá trị đã lưu.

### Diagnostics

Cung cấp các phép kiểm tra:

- API.
- Wi-Fi/DNS.
- Storage.
- Worker heartbeat.
- Tool availability.
- Telegram.
- Authentication.
- Firmware/OS version.
- Log gần nhất.

Trên ESP32, giữ UI nhẹ, không cấp phát bộ nhớ lớn liên tục và không để thao tác mạng chặn task giao diện.

## 13. Adaptive download controller

Triển khai adaptive controller có giới hạn an toàn.

Không quyết định số kết nối chỉ dựa trên speed test.

Đánh giá các tín hiệu:

- Throughput thực tế theo cửa sổ thời gian.
- Tỷ lệ lỗi.
- Timeout.
- HTTP 429 hoặc lỗi giới hạn truy cập.
- Số job đồng thời.
- CPU và RAM trên Windows khi có thể đo.
- Dung lượng/hiệu suất ghi ổ đĩa.
- Khả năng của host và downloader.
- Lịch sử hiệu suất gần đây.

Điều chỉnh số job và số kết nối phân đoạn riêng biệt.

Bắt đầu bằng giá trị bảo thủ. Tăng dần khi ổn định, giảm khi lỗi tăng. Đặt giới hạn trên và dưới. Không cố vượt qua cơ chế giới hạn hoặc kiểm soát truy cập của website.

Nếu không đủ dữ liệu, dùng cấu hình tĩnh an toàn và hiển thị rằng adaptive mode chưa đủ dữ liệu.

## 14. Bảo mật

Triển khai bảo mật thực chất:

- Mật khẩu quản trị được thiết lập trong lần khởi động đầu tiên.
- Chỉ lưu verifier/hash thích hợp, không lưu plaintext.
- Session/token có thời hạn.
- Không đưa token vào URL.
- Che secret trên tất cả giao diện.
- Không log password, bot token, worker credential hoặc session token.
- Rate limit đăng nhập.
- Kiểm tra quyền trên từng endpoint.
- Giới hạn kích thước dữ liệu đầu vào.
- Kiểm tra scheme và độ dài URL.
- Ngăn path traversal.
- Không cung cấp remote shell.
- Không mở API ra Internet theo mặc định.
- Không để worker credential có quyền quản trị toàn hệ thống.
- Có quy trình thu hồi và cấp lại credential.

Trên mạng LAN, không mặc định mọi client đều đáng tin cậy. Phải yêu cầu xác thực cho thao tác thay đổi cấu hình, quản lý queue hoặc xem dữ liệu nhạy cảm.

## 15. Quy trình thực thi bắt buộc

Thực hiện các giai đoạn dưới đây liên tục, không dừng lại để hỏi có muốn làm tiếp hay không.

### Phase A — Audit

1. Kiểm tra repository, tệp hiện có và Git status.
2. Kiểm tra toolchain khả dụng.
3. Đọc các tài liệu kiến trúc và schema hiện có.
4. Xác định module đã có, module còn thiếu, lỗi build và dependency.
5. Tạo `docs/EXECUTION_STATUS.md` và cập nhật liên tục.

### Phase B — Shared contract

1. Hoàn thiện schema cấu hình.
2. Hoàn thiện mô hình job/state machine.
3. Hoàn thiện API contract.
4. Định nghĩa error codes, auth, idempotency, worker lease và event sequence.
5. Viết contract tests.
6. Chạy test và sửa lỗi.

### Phase C — ESP32 control plane

1. Khởi động firmware và health diagnostics.
2. Storage abstraction.
3. Persistent settings.
4. Durable queue.
5. API authentication.
6. Settings API.
7. Job API.
8. Worker registry/lease/recovery.
9. LVGL screens.
10. Telegram integration.
11. Host-side tests và firmware build.

### Phase D — Windows runtime

1. Tạo hoặc hoàn thiện Worker Service.
2. Tích hợp pairing và polling/claim.
3. Tool discovery.
4. Downloader process manager.
5. Progress reporting.
6. Output verification.
7. Cancellation.
8. Retry/recovery.
9. Installer/uninstaller.
10. Chạy unit tests và integration tests.
11. Cài/chạy service nếu môi trường cho phép.

### Phase E — Android

1. Tạo ứng dụng native.
2. Pairing/discovery.
3. API client.
4. Authentication.
5. Dashboard.
6. Queue management.
7. Settings.
8. Telegram/Worker configuration.
9. Diagnostics/logs.
10. Chạy unit tests và build APK.

### Phase F — Web UI

1. Xây giao diện thật.
2. Tích hợp API.
3. Tích hợp mọi trang cấu hình.
4. Thêm trạng thái loading/empty/error/offline.
5. Build asset tối ưu.
6. Kiểm tra bundle size và khả năng phục vụ từ ESP32.

### Phase G — Integration

1. Chạy ESP32 API test với môi trường giả lập nếu không có phần cứng.
2. Chạy Windows worker với fake downloader.
3. Chạy end-to-end test từ submit URL tới kết quả worker.
4. Kiểm tra reconnect và duplicate events.
5. Kiểm tra cấu hình đồng bộ.
6. Kiểm tra xử lý khi Telegram chưa cấu hình.
7. Kiểm tra worker chưa pairing.
8. Kiểm tra storage lỗi.
9. Kiểm tra job recovery.
10. Sửa lỗi và chạy lại.

### Phase H — Release

Tạo đầy đủ:

- Firmware build output nếu build thành công.
- Android APK nếu build thành công.
- Windows Service publish output và installer/script cài đặt.
- Web UI production build.
- Mẫu cấu hình không chứa secret thật.
- Hướng dẫn cài đặt từ đầu.
- Hướng dẫn pairing.
- Hướng dẫn cấu hình Telegram.
- Hướng dẫn đổi IP/hostname và output folder.
- Hướng dẫn backup/restore.
- Hướng dẫn chẩn đoán.
- Báo cáo test.
- Bảng chức năng đã hoàn thành/chưa hoàn thành.
- Danh sách thao tác vật lý cuối cùng nếu cần.

## 16. Ma trận kiểm thử bắt buộc

Tối thiểu phải kiểm tra các trường hợp:

### Cấu hình

- Thiếu Telegram token.
- Token sai.
- Worker chưa pairing.
- Output path chưa cấu hình.
- IP thay đổi.
- Secret đã lưu nhưng không được trả plaintext.
- Cấu hình hợp lệ sau reboot.
- Cấu hình sai bị từ chối.
- Cập nhật một trường không làm mất các trường khác.

### Queue

- Thêm URL hợp lệ.
- URL không hợp lệ.
- URL trùng.
- Hai request đồng thời.
- Lưu thất bại.
- Storage đầy.
- Worker offline.
- Worker khởi động lại.
- ESP32 reboot khi có job.
- Retry vượt giới hạn.
- Cancel job.
- Re-download.
- Delete history không xóa output file.

### Downloader

- Thiếu yt-dlp.
- Thiếu FFmpeg.
- Sai đường dẫn công cụ.
- Output volume không khả dụng.
- Không đủ dung lượng.
- Process exit code khác 0.
- Không tạo output.
- Tạo output rỗng hoặc không hợp lệ.
- Network timeout.
- Graceful cancellation.
- Service restart khi đang tải.

### Giao tiếp

- API mất kết nối.
- Worker heartbeat hết hạn.
- Event trùng.
- Event sai thứ tự.
- Client reconnect.
- API version không tương thích.
- Authentication thất bại.
- Unauthorized access.
- Request quá lớn.

### Giao diện

- Loading.
- Empty state.
- Error state.
- Offline state.
- Validation error.
- Save success/failure.
- Secret masking.
- Khởi động lại và đọc lại cấu hình.
- Đồng bộ trạng thái giữa CYD, web và Android.

## 17. Tiêu chí hoàn thành

Chỉ đánh dấu hoàn thành khi có bằng chứng tương ứng.

### Build

- Firmware build thành công trong môi trường có ESP-IDF phù hợp.
- Windows Service build/publish thành công.
- Android app build thành công.
- Web UI production build thành công.

### Tests

- Contract tests pass.
- Unit tests pass.
- Integration tests pass ở các phần có thể chạy.
- Fake-worker end-to-end test pass.
- Cấu hình được lưu và khôi phục sau restart trong test.
- Các failure cases chính được kiểm tra.

### Operability

- Có quy trình cài đặt Windows Service.
- Có quy trình build/flash firmware.
- Có APK hoặc lệnh build APK.
- Có Web UI build.
- Có hướng dẫn khởi tạo quản trị.
- Có thể nhập cấu hình từ giao diện.
- Có thể kết nối worker.
- Có thể thêm và quản lý job.
- Có tiến trình thực từ worker.
- Có kết quả tải được xác minh.
- Có hướng dẫn cấu hình Telegram.
- Không cần sửa source code để nhập các thông tin cấu hình thông thường.

Nếu môi trường thiếu thiết bị vật lý, tài khoản bên ngoài hoặc quyền cài đặt, phải hoàn thiện mọi phần khác có thể làm được. Chỉ đánh dấu các bước phụ thuộc đó là `BLOCKED_EXTERNAL`, nêu chính xác lý do và cách hoàn tất.

Không đánh dấu một bài test là PASS nếu chưa thực thi nó.

## 18. Báo cáo cuối cùng

Khi kết thúc, tạo báo cáo `docs/FINAL_REPORT.md` bao gồm:

1. Tóm tắt hệ thống thực sự đã triển khai.
2. Cấu trúc repository cuối cùng.
3. Những module đã hoàn thành.
4. Những module chưa hoàn thành và lý do cụ thể.
5. Kết quả build thật của từng thành phần.
6. Lệnh kiểm thử đã chạy.
7. Số lượng pass/fail/blocked.
8. Các lỗi đã phát hiện và sửa.
9. Đường dẫn chính xác tới firmware, APK, Windows Service và Web UI build output.
10. Hướng dẫn triển khai từ đầu.
11. Các trường cấu hình phải nhập sau cài đặt và vị trí nhập chúng trên CYD, web và Android.
12. Hướng dẫn pairing Windows worker.
13. Hướng dẫn cấu hình Telegram.
14. Checklist kết nối và vận hành lần đầu.
15. Các bước kiểm tra phần cứng còn cần người dùng thực hiện nếu agent không có thiết bị thật.

Không chỉ báo cáo phần đã làm; phải báo cáo cả phần chưa làm và bằng chứng.

## 19. Lệnh bắt đầu

**BẮT ĐẦU THỰC THI NGAY.**

Hãy kiểm tra repository hiện tại, xác định toolchain, đọc tài liệu và bắt đầu triển khai trên chính repository đó.

Không trả lời bằng một kế hoạch rồi dừng. Không hỏi người dùng lựa chọn kỹ thuật. Không đợi Telegram token hoặc dữ liệu cấu hình mới bắt đầu.

Tự đưa ra các quyết định kỹ thuật hợp lý, ghi lại trong tài liệu, triển khai mã nguồn, chạy test, sửa lỗi, build các thành phần và tiếp tục cho đến khi đạt mức hoàn thiện cao nhất có thể trong môi trường hiện tại.

**Định nghĩa thành công: người dùng nhận được một sản phẩm có mã nguồn và artifact thực tế, có thể cài đặt, nhập các thông tin còn thiếu trực tiếp từ giao diện, kết nối các thành phần và vận hành mà không phải quay lại yêu cầu agent viết tiếp những phần cơ bản còn bỏ trống.**
