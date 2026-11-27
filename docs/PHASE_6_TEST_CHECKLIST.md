# Fase 6 — Checklist uji sinkronisasi di HP

Bagian ini tidak bisa diuji otomatis karena membutuhkan project Supabase-mu, akunmu, dan HP
sungguhan. Centang setiap langkah. Kalau ada yang gagal, catat nomor langkah dan pesan yang muncul.

## A. Persiapan Supabase (sekali saja)
- [ ] A1. SQL Editor → jalankan seluruh isi `supabase/migrations/0001_initial.sql`. Hasilnya "Success".
- [ ] A2. Table Editor menampilkan 5 tabel: wallets, categories, transactions, budgets, financial_goals.
- [ ] A3. Authentication → Users → Add user → Create new user (email + password, centang *Auto Confirm User*).
- [ ] A4. Authentication → Sign In / Providers → matikan *Allow new users to sign up*.

## B. HP utama (yang sudah berisi data)
- [ ] B1. Install APK baru di atas versi lama. Data lama tetap ada.
      **Penting:** kalau Android menolak ("Aplikasi tidak terinstal" / konflik paket), **jangan
      uninstall** versi lama. Uninstall menghapus semua data lokal dan tidak ada backup Android
      (`allowBackup=false`). Laporkan dulu; data bisa diamankan lewat sinkronisasi dari versi
      yang benar.
- [ ] B2. Pengaturan → Sinkronisasi cloud muncul.
- [ ] B3. Masuk dengan password salah → "Email atau kata sandi salah."
- [ ] B4. Masuk dengan password benar → "Masuk sebagai …".
- [ ] B5. Dalam ±1 menit: "Terakhir sinkron: …" dan "Semua perubahan sudah tersimpan di cloud."
- [ ] B6. Di Table Editor Supabase, jumlah baris `transactions` sama dengan jumlah transaksi di HP (termasuk yang dihapus, karena dihapus = `deleted_at` terisi).
- [ ] B7. Tambah 1 pengeluaran. Dalam ±1 menit muncul di tabel `transactions`.
- [ ] B8. Mode pesawat → tambah transaksi → "1 perubahan menunggu dikirim". Matikan mode pesawat → terkirim sendiri.
- [ ] B9. Tutup aplikasi, buka lagi → tidak diminta login lagi.

## C. Pemulihan di perangkat kedua (atau setelah install ulang di HP lain/emulator)
- [ ] C1. Install APK → Pengaturan → Sinkronisasi cloud → Masuk.
- [ ] C2. Semua dompet, kategori (termasuk nama kategori yang sudah kamu ubah), transaksi, anggaran dan target muncul.
- [ ] C3. Saldo setiap dompet sama persis dengan HP utama.
- [ ] C4. Ubah satu transaksi di perangkat kedua → setelah "Sinkronkan sekarang" di HP utama, perubahan muncul.
- [ ] C5. Hapus satu transaksi di HP utama → hilang juga di perangkat kedua setelah sinkron.

## D. Keamanan
- [ ] D1. Keluar → data di HP tetap ada; status kembali ke form Masuk.
- [ ] D2. Coba buat akun kedua di dashboard lalu masuk dengan akun itu di HP utama → muncul
      "Data di perangkat ini milik akun lain…" dan tidak ada data yang terkirim ke akun kedua.
      (Hapus akun kedua itu setelahnya.)
- [ ] D3. Di Table Editor, kolom `user_id` semua baris sama dengan id akunmu.

Catatan: APK debug dari sesi ini ditandatangani dengan debug key. Untuk distribusi, gunakan build
release yang ditandatangani dengan keystore milikmu sendiri (lihat ENGINEERING_SPEC §9).
