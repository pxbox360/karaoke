อัปเดตเวอร์ชัน 2 (อ่านค่าจากรถจริง)

ในโปรเจกต์ Android Studio เดิม:
1) แทนที่ app/src/main/AndroidManifest.xml ด้วยไฟล์ AndroidManifest.xml ที่แนบ
2) แทนที่ MainActivity.kt ด้วยไฟล์ที่แนบ (ตรวจบรรทัดแรก package ให้ตรงกับโปรเจกต์)
3) แทนที่ app/src/main/assets/index.html ด้วยไฟล์ในโฟลเดอร์ assets
4) Build > Build APK(s) แล้วติดตั้งลงรถเหมือนเดิม (ถ้ามีเวอร์ชันเก่าอยู่ ติดตั้งทับได้)

เปิดแอพบนรถ:
- มุมขวาบนจะขึ้น LIVE ถ้าอ่านจากรถได้ (กดสลับเป็น DEMO ได้)
- ไปแท็บ Diagnostics แล้วกด Save (หรือถ่ายภาพหน้าจอ) ส่งให้ผม
- ถ้าเสียบ adb: adb pull /sdcard/Android/data/com.example.sealbattery/files/probe.txt
