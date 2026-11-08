# MT Reader – bản Việt hóa (dịch truyện tranh sang tiếng Việt)

Bản sửa đổi dựa trên mã nguồn mở [jedzqer/manga-translator-android](https://github.com/jedzqer/manga-translator-android) (giấy phép AGPL-3.0, commit `b729828`).

## Đã thêm gì

1. **Tiếng Việt là ngôn ngữ đích.** Trong app này, ngôn ngữ đích đi theo ngôn ngữ giao diện. Chọn giao diện Tiếng Việt thì mọi bản dịch ra tiếng Việt.
   - Toàn bộ giao diện app đã dịch sang tiếng Việt (683 chuỗi).
   - Có bộ prompt dịch tiếng Việt riêng, kèm hướng dẫn xưng hô (tôi/cậu, tao/mày…), cách giữ tên riêng và cách dịch hiệu ứng âm thanh (BOOM → BÙM).
   - Văn phong mặc định viết cho người đọc Việt (bản gốc mặc định là văn phong tiếng Trung).
2. **Mục "Công cụ dịch"** (Cài đặt → mục API, nút đầu tiên), gồm 3 lựa chọn:
   - **Dịch vụ AI (API key):** như bản gốc. Chất lượng tốt nhất, xưng hô nhất quán, tốn phí theo nhà cung cấp.
   - **Google Dịch offline (miễn phí):** dùng Google ML Kit, không cần key. Lần đầu tải gói ngôn ngữ (vài chục MB), sau đó dịch không cần mạng. Chất lượng thấp hơn dịch online.
   - **Google Cloud Translation (API key):** chất lượng như Google Dịch online. 500.000 ký tự đầu mỗi tháng miễn phí.
   - Hai chế độ Google tự chuyển chữ IN HOA kiểu truyện Mỹ sang chữ thường trước khi dịch, vì để nguyên chữ in hoa thì Google dịch sai nhiều.
3. **Tự build APK trên GitHub** (file `.github/workflows/build-apk.yml`), không cần cài Android Studio.

## Cách tạo file APK (không cần cài gì lên máy tính)

1. Tạo tài khoản tại https://github.com (miễn phí).
2. Mở https://github.com/jedzqer/manga-translator-android, bấm **Fork** rồi **Create fork**.
3. Giải nén file `manga-translator-vi-changes.zip`. Bên trong có các thư mục `.github`, `app`, `assets` và file hướng dẫn này.
4. Trong bản fork của bạn, bấm **Add file → Upload files**. Kéo **cả 3 thư mục** `.github`, `app`, `assets` cùng file hướng dẫn vào trang, rồi bấm **Commit changes**. GitHub giữ nguyên đường dẫn thư mục và ghi đè các file cũ.
   - Thư mục `.github` có thể bị ẩn: trên Mac bấm `Cmd + Shift + .`; trên Windows bật "Hidden items" trong File Explorer.
   - Nếu vẫn không tải lên được thư mục `.github`: vào tab **Actions → set up a workflow yourself**, đặt tên file là `build-apk.yml`, dán nội dung file `.github/workflows/build-apk.yml` vào rồi **Commit**.
5. Vào tab **Actions**. Bản fork mặc định tắt Actions, nên bấm **I understand my workflows, go ahead and enable them**.
6. Chọn **Build APK** ở cột trái, rồi bấm **Run workflow → Run workflow**.
7. Đợi khoảng 15–30 phút, đến khi có dấu ✅. Bấm vào lần chạy đó, kéo xuống mục **Artifacts** và tải **manga-translator-apk**. Giải nén ra file `app-debug.apk`.
8. Chép APK vào điện thoại rồi mở để cài (cho phép "Cài ứng dụng không rõ nguồn gốc" khi được hỏi).

> Nếu bước build báo ❌, mở lần chạy, bấm vào bước bị đỏ, sao chép đoạn lỗi và gửi cho Claude để sửa.

## Thiết lập lần đầu trong app

1. **Cài đặt → Cài đặt ngôn ngữ → Tiếng Việt.** Bước này bắt buộc: nó quyết định ngôn ngữ đích của bản dịch.
2. **Cài đặt → Công cụ dịch**, rồi chọn một trong ba:
   - **Google Dịch offline:** dùng được ngay, miễn phí.
   - **Google Cloud Translation:** dán API key (cách lấy key ở dưới).
   - **Dịch vụ AI:** nhập địa chỉ API, API key và tên mô hình ở các ô bên dưới.
3. **Nhập truyện:** Thư viện → Thao tác nhanh → **Nhập từ CBZ/ZIP/PDF**, rồi chọn file zip truyện.
4. **Cài đặt dịch cho thư mục truyện:**
   - **Ngôn ngữ nguồn:** Tiếng Anh.
   - **Khi dùng Google:** tắt "Dịch VL toàn trang" và "Dịch toàn văn", vì hai chế độ này cần dịch vụ AI.
   - **Tùy chọn:** bật "Chỉ dịch bong bóng" để app không phủ màu lên chữ hiệu ứng vẽ đè trên tranh.
5. Bấm **Dịch**. Xong thì mở **Đọc truyện** để xem, kéo hoặc sửa bong bóng nếu cần.
6. **Xuất file:** **Xuất truyện → Xuất thành CBZ (file zip)**. File CBZ chính là file zip chứa ảnh: muốn mở bằng app giải nén thì đổi đuôi `.cbz` thành `.zip`.

## Lấy Google Cloud API key (chỉ khi chọn Google Cloud Translation)

1. Vào https://console.cloud.google.com và tạo một Project mới.
2. Vào **APIs & Services → Library**, tìm **Cloud Translation API** rồi bấm **Enable**.
3. Bật thanh toán (Billing) cho project. Google bắt buộc bước này kể cả khi bạn chỉ dùng hạn mức miễn phí 500.000 ký tự/tháng.
4. Vào **APIs & Services → Credentials → Create credentials → API key**.
5. Bấm vào key vừa tạo, chọn **Restrict key → Cloud Translation API** để key chỉ dùng được cho việc dịch.
6. Dán key vào app: **Cài đặt → Công cụ dịch → Google Cloud Translation**.

## Lưu ý

- **Cách xóa chữ gốc:** app phủ bong bóng bằng màu nền lấy mẫu rồi viết chữ Việt lên. Bong bóng nền trắng hoặc nền trơn sẽ sạch. Chữ nằm đè lên tranh thì sẽ thành một mảng màu che tranh.
- **Font chữ:** font mặc định của Android hiển thị đúng dấu tiếng Việt. Nếu muốn font kiểu truyện tranh, tải lên font có hỗ trợ tiếng Việt (ví dụ các font "Việt hóa") trong **Cài đặt font**.
- **Thông báo cập nhật:** app vẫn kiểm tra cập nhật từ dự án gốc. Hãy **bỏ qua** các thông báo này, vì bản gốc không có tiếng Việt và mục Google Dịch.
- **Xung đột với bản gốc:** nếu điện thoại đã cài bản gốc của tác giả, gỡ bản đó trước khi cài APK này (hai bản dùng chữ ký khác nhau).
- **Giấy phép AGPL-3.0:** nếu bạn chia sẻ APK này cho người khác, phải công khai mã nguồn bản sửa. Để bản fork trên GitHub ở chế độ công khai là đáp ứng yêu cầu này.
