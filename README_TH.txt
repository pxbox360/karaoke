Seal Battery v0.3 : โปรเจกต์ Android Studio ครบชุด

วิธีใช้ (ครั้งแรกราว 10-15 นาที ต้องมีอินเทอร์เน็ตให้ Android Studio โหลดไฟล์)
1) แตกไฟล์ zip แล้วเปิด Android Studio > Open > เลือกโฟลเดอร์ SealBattery
2) รอ "Gradle sync" ด้านล่างจอเสร็จ (ถ้าถามให้ติดตั้ง SDK/Update ให้กดยอมรับ)
3) เมนู Build > Build APK(s) > กด "locate"
   ไฟล์: app/build/outputs/apk/debug/app-debug.apk
4) ก๊อปใส่แฟลชไดรฟ์ (exFAT) แล้วติดตั้งบนรถ (ติดตั้งทับเวอร์ชันเก่าได้)

ตั้งค่าไว้แล้ว: minSdk 29, targetSdk 33, แนวนอน, เต็มจอ
แก้หน้าจอได้ที่ app/src/main/assets/index.html
ไฟล์ที่อ่านค่าจากรถ: app/src/main/java/com/example/sealbattery/MainActivity.kt

ถ้า sync ฟ้องเรื่องเวอร์ชัน Gradle/AGP ให้กด "Update"/"Upgrade" ตามที่ Android Studio แนะนำ
