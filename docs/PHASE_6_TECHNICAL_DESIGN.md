# FinTrack — Fase 6: Cloud Sync (Supabase) — Technical Design

Status: **Usulan, menunggu persetujuan.** Belum ada implementasi.
Basis kode: commit `9d6b96f` (branch `claude/review-engineering-rules-fu16n6`), schema Room versi 2.
Acuan: `CLAUDE.md`, `docs/ENGINEERING_SPEC.md`.

Keputusan pengguna yang sudah diambil:
- Supabase dipakai untuk sinkronisasi/backup otomatis (bukan file backup manual).
- Login **email + password, sekali per perangkat**; sesi disimpan, tidak diminta lagi.
- Izin `INTERNET` boleh ditambahkan.
- Pemakaian pribadi: satu akun. Pendaftaran akun baru dimatikan di Supabase setelah akun dibuat.

---

## 1. Ringkasan

Room tetap **satu-satunya sumber kebenaran** di perangkat; aplikasi tetap berjalan penuh tanpa
internet dan tanpa login. Supabase adalah salinan cloud yang disinkronkan di latar belakang:

- **Push:** baris lokal dengan `syncStatus = PENDING` di-*upsert* ke Supabase, lalu ditandai
  `SYNCED` hanya bila baris itu tidak berubah selama dikirim.
- **Pull:** baris yang berubah di server sejak *cursor* terakhir diambil dan digabung ke Room.
- **Konflik:** *last-writer-wins* per baris berdasarkan `updatedAt`, ditegakkan di dua sisi.
- **Tidak ada hard delete** di mana pun; penghapusan selalu `deletedAt` (soft delete) dan ikut
  tersinkron.
- Saldo dompet tetap **dihitung** dari transaksi, sehingga otomatis konsisten setelah pull.

## 2. Audit kode saat ini (yang memengaruhi desain)

| Temuan | Akibat untuk desain |
|---|---|
| Semua repository sudah menulis `syncStatus = PENDING` (dan `updatedAt`) pada setiap create/edit/archive/soft delete, termasuk impor CSV dan planning. | Antrian push = `WHERE syncStatus = 'PENDING'`. Tabel `sync_outbox` (schema v1) **tidak diperlukan** dan tetap tidak dipakai; tidak ada migrasi Room. |
| Kategori bawaan memakai UUID deterministik dari key (`DefaultCategories`). | Kategori bawaan punya ID sama di semua perangkat → tidak terduplikasi. |
| Seeding menulis `updatedAt = waktu install`. | Di HP kedua, seed baru akan "lebih baru" dan **menimpa** kategori bawaan yang sudah diubah di HP pertama. Perbaikan: seed baru ditulis dengan `updatedAt = 0` (lihat §6.4). |
| Semua timestamp internal adalah epoch millis UTC (`Long`). | Server menyimpan kolom yang sama sebagai `bigint` → round-trip persis, tanpa konversi zona waktu. |
| Room menegakkan foreign key (wallet, category, parent category, budget→category). | Urutan pull/push wajib: wallets → categories (induk dulu) → transactions → budgets → goals. |

## 3. Arsitektur

```
UI (Pengaturan → Sinkronisasi)
   │
SyncViewModel ── AuthRepository ── supabase-kt Auth (sesi)
   │
SyncScheduler (WorkManager) ── SyncWorker ── SyncEngine (Kotlin murni, diuji)
                                               │            │
                                         LocalSyncStore   RemoteSyncSource (interface)
                                          (Room DAO)        └─ SupabaseRemoteSource (PostgREST)
```

- `SyncEngine` tidak mengenal Supabase maupun Android: ia bekerja dengan `LocalSyncStore` dan
  `RemoteSyncSource`, sehingga seluruh logika sinkronisasi diuji dengan *fake remote* di JVM.
- Konfigurasi: `supabase.url` dan `supabase.publishableKey` di `local.properties` (git-ignored)
  → `BuildConfig`. Bila kosong, fitur sinkronisasi disembunyikan dan aplikasi berjalan seperti
  sekarang. Publishable key bukan rahasia (ikut di APK); keamanan berasal dari RLS. Secret key /
  service_role **tidak pernah** dipakai di aplikasi maupun repo.

### Dependensi baru (versi diverifikasi saat implementasi, kompatibel Kotlin 2.4 / AGP 9.5)

| Library | Fungsi |
|---|---|
| supabase-kt (`auth-kt`, `postgrest-kt`) + Ktor OkHttp engine | Login, refresh token, REST ke PostgREST |
| Plugin `kotlinx-serialization` | DTO JSON untuk supabase-kt |
| WorkManager (`work-runtime-ktx`) + `hilt-work` | Sinkronisasi latar belakang dengan constraint jaringan |
| DataStore Preferences | Status per perangkat: cursor pull, user id terakhir, waktu sinkron terakhir |

Alternatif yang ditolak: OkHttp + PostgREST manual (harus menulis sendiri refresh token dan
penanganan sesi — lebih banyak kode keamanan sendiri).

## 4. Skema server (Postgres di Supabase)

File: `supabase/migrations/0001_initial.sql` di repo. **Kamu jalankan sendiri di SQL Editor
Supabase**; aplikasi dan asisten tidak butuh akses admin.

Untuk setiap tabel (`wallets`, `categories`, `transactions`, `budgets`, `financial_goals`):

- Kolom sama dengan Room (nama snake_case), plus `user_id uuid not null default auth.uid()`
  dan `server_updated_at timestamptz not null` (diisi trigger, dipakai sebagai cursor pull).
- **Primary key `(user_id, id)`** — ID kategori bawaan sama untuk semua orang, jadi ID saja
  tidak boleh jadi kunci global.
- Foreign key komposit `(user_id, wallet_id) → wallets(user_id, id)` dst., sama seperti Room.
- `CHECK` yang mencerminkan aturan bisnis: `amount > 0` (bigint), `type in (...)`, transfer wajib
  `destination_wallet_id` berbeda dan `category_id is null`, income/expense wajib kategori,
  `currency = 'IDR'`, dst.
- **RLS aktif** di semua tabel. Policy: `select`, `insert`, `update` hanya bila
  `user_id = auth.uid()`. **Tidak ada policy `delete`** → hard delete mustahil dari aplikasi.
- Trigger `BEFORE INSERT OR UPDATE`:
  1. set `server_updated_at = clock_timestamp()`;
  2. pada update, bila `NEW.updated_at < OLD.updated_at` → `RETURN NULL` (abaikan tulisan lama;
     penegakan last-writer-wins di server).
- Index `(user_id, server_updated_at)` untuk pull.

Pengujian SQL: skema, CHECK, trigger dan RLS dijalankan pada PostgreSQL lokal di container
(dengan fungsi `auth.uid()` tiruan), bila PostgreSQL dapat dipasang di lingkungan build.
Bila tidak bisa, hal itu dilaporkan dan SQL hanya diuji di Supabase olehmu.

## 5. Autentikasi

- Layar **Masuk** (email + password) di Pengaturan → Sinkronisasi. Tidak ada layar daftar;
  akun dibuat sekali di dashboard Supabase (Authentication → Users → Add user), lalu
  pendaftaran publik dimatikan.
- Sesi (access + refresh token) dikelola supabase-kt dan disimpan di penyimpanan privat aplikasi
  (tidak ikut backup: `allowBackup=false`). Token tidak pernah di-log.
- **Keluar:** sinkronisasi berhenti, sesi dihapus, **data lokal tetap ada**.
- Perangkat mengingat `user_id` terakhir. Bila login dengan akun lain sementara ada data lokal,
  sinkronisasi ditolak dengan penjelasan (mencegah data dua akun tercampur).
- Belum login = aplikasi offline seperti sekarang; tidak ada fitur yang dikunci.

## 6. Algoritma sinkronisasi

Satu putaran (`SyncEngine.run`), dijalankan berurutan, tidak paralel (unique work):

### 6.1 Push
Per tabel dalam urutan FK (wallets → categories induk → subkategori → transactions → budgets →
goals):
1. Baca baris `PENDING` (batch maks 500).
2. `upsert` ke server (`on conflict (user_id, id)`), seluruh baris.
3. Untuk setiap baris yang terkirim: `UPDATE … SET syncStatus='SYNCED' WHERE id=? AND
   updatedAt=?` — bila baris diubah lagi selama pengiriman, ia tetap `PENDING`.
4. Bila server mengabaikan baris karena versinya lebih baru (trigger §4), versi server datang
   lewat pull di langkah berikutnya.

### 6.2 Pull
1. Ambil baris `server_updated_at > cursor − 5 menit` (overlap untuk transaksi Postgres yang
   commit terlambat; penggabungan idempoten), urut `server_updated_at`, per halaman 500.
2. Gabung per baris:
   - baris lokal tidak ada → insert (`SYNCED`);
   - baris lokal `SYNCED` → ganti dengan versi server bila `remote.updatedAt >= local.updatedAt`;
   - baris lokal `PENDING` dan `local.updatedAt > remote.updatedAt` → **lokal menang**, tetap
     `PENDING`, dikirim di push berikutnya; selain itu versi server menang.
3. Validasi sebelum menulis: transaksi dari server dicek dengan aturan struktural
   `TransactionValidator` (jumlah > 0, kombinasi tipe/dompet/kategori, referensi ada). Aturan
   "arsip tidak boleh dipilih" tidak berlaku untuk data historis. Baris yang gagal **tidak
   ditulis**, dicatat, dan ditampilkan ("N data dari cloud ditolak"); sinkronisasi lain lanjut.
4. Cursor disimpan di DataStore **setelah** halaman berhasil ditulis dalam satu transaksi Room.

### 6.3 Penjadwalan (WorkManager)
- Setelah setiap perubahan lokal: one-time work unik dengan jeda ±10 detik (perubahan beruntun
  digabung), constraint `NetworkType.CONNECTED`.
- Saat aplikasi dibuka dan periodik setiap 6 jam (minimum Android 15 menit).
- Gagal jaringan / 5xx → retry dengan exponential backoff. 401 → refresh token; gagal →
  status "Perlu masuk lagi". Error lain → status error dengan pesan Bahasa Indonesia.

### 6.4 Login pertama per perangkat
| Kondisi | Perilaku |
|---|---|
| Lokal ada data, server kosong (HP utama sekarang) | Push semua (semua baris sudah `PENDING`). |
| Lokal hanya kategori bawaan, server ada data (HP baru / install ulang) | Pull semua; seed lokal (`updatedAt = 0`) kalah dari versi server. |
| Keduanya punya data | Digabung per baris dengan aturan §6.2; tidak ada yang dihapus. |

Perubahan kecil pada seeding: kategori bawaan **baru** ditulis dengan `updatedAt = 0` agar setiap
perubahan nyata selalu menang. Install yang sudah ada tidak diubah.

### 6.5 Batasan yang diketahui (didokumentasikan, tidak "diperbaiki diam-diam")
- Last-writer-wins memakai jam perangkat; jam HP yang salah bisa memenangkan edit yang lebih lama.
- Edit offline di dua HP pada baris yang sama → hanya edit terakhir yang dipertahankan.
- Dua HP offline yang sama-sama membuat anggaran untuk kategori yang sama → muncul dua anggaran;
  pengguna menghapus salah satunya.
- Sinkronisasi bukan realtime; jeda normal beberapa detik sampai beberapa menit.

## 7. UI (Pengaturan → Sinkronisasi cloud)
- Belum dikonfigurasi (BuildConfig kosong): item tidak tampil.
- Belum masuk: penjelasan singkat + tombol **Masuk**.
- Sudah masuk: email, "Terakhir sinkron: Kamis, 27 November 2026 10.15 WIB", jumlah perubahan
  menunggu, tombol **Sinkronkan sekarang**, tombol **Keluar** (dengan konfirmasi bahwa data lokal
  tetap ada).
- Error ditampilkan apa adanya (tanpa jaringan, perlu masuk lagi, data ditolak).

## 8. Keamanan
- `INTERNET` ditambahkan; hanya host Supabase yang dihubungi (tidak ada analytics/iklan).
- RLS + tanpa policy delete; publishable key saja di aplikasi.
- Tidak ada log berisi jumlah uang, deskripsi, token, atau email.
- `local.properties` tetap git-ignored; konfigurasi tidak pernah di-commit.

## 9. Strategi pengujian
- `SyncEngine` dengan fake remote (JVM/Robolectric + Room in-memory): push lalu SYNCED,
  perubahan selama push tetap PENDING, pull insert/replace, konflik dua arah, overlap cursor
  idempoten, urutan FK, soft delete tersinkron, baris server tidak valid ditolak, kegagalan di
  tengah halaman tidak memajukan cursor, login akun berbeda ditolak.
- Mapping DTO ↔ entity round-trip untuk setiap tabel.
- SQL server di PostgreSQL lokal (§4) bila tersedia.
- ViewModel + UI test layar Sinkronisasi.
- **Uji end-to-end dengan project Supabase-mu dilakukan olehmu di HP** (daftar langkah disediakan),
  karena container tidak bisa menjalankan aplikasi dan login memakai password-mu.

## 10. Urutan implementasi (commit per langkah)
| Langkah | Isi |
|---|---|
| 6.0 | Konfigurasi BuildConfig dari `local.properties`, izin INTERNET, dependensi, seed `updatedAt = 0` |
| 6.1 | `supabase/migrations/0001_initial.sql` + pengujian SQL |
| 6.2 | `SyncEngine`, `LocalSyncStore`, DTO, fake remote, tes |
| 6.3 | `SupabaseRemoteSource`, `AuthRepository` |
| 6.4 | WorkManager, layar Sinkronisasi + Masuk, string Bahasa Indonesia |
| 6.5 | Update `ENGINEERING_SPEC`, panduan setup Supabase + checklist uji di HP |

## 11. Keputusan yang perlu disetujui
1. Antrian push memakai `syncStatus` yang sudah ada; `sync_outbox` tidak dipakai.
2. Last-writer-wins per baris berdasarkan `updatedAt`, ditegakkan di aplikasi dan trigger server.
3. Primary key server `(user_id, id)`; tanpa policy delete.
4. Library: supabase-kt + WorkManager + DataStore.
5. Seed kategori bawaan baru dengan `updatedAt = 0`.
6. Sesi disimpan di penyimpanan privat aplikasi (tanpa enkripsi tambahan Keystore).
7. Interval periodik 6 jam + sinkron setelah perubahan dan saat aplikasi dibuka.
