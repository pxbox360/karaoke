วิธีสร้าง APK (ทำครั้งแรกประมาณ 15-20 นาที)

1) ติดตั้ง Android Studio จาก developer.android.com/studio
2) New Project > "Empty Views Activity"
   - Name: Seal Battery
   - Package name: com.example.sealbattery
   - Language: Kotlin
   - Minimum SDK: API 29
3) เปิดไฟล์ app/build.gradle(.kts) แล้วแก้ในบล็อก defaultConfig:
      minSdk = 29
      targetSdk = 33
4) เปลี่ยนไฟล์ MainActivity.kt ในโปรเจกต์ ให้เป็นเนื้อหาจากไฟล์ MainActivity.kt ที่แนบ
5) คลิกขวาที่ app > New > Folder > Assets Folder แล้ววางไฟล์ index.html ลงใน app/src/main/assets/
6) เมนู Build > Build APK(s) แล้วกด "locate"
   ไฟล์อยู่ที่ app/build/outputs/apk/debug/app-debug.apk
7) ก๊อปไฟล์ APK ใส่แฟลชไดรฟ์ (exFAT) เสียบรถ แล้วติดตั้งผ่านตัวจัดการไฟล์
