# FinTrack — Fase 4: Advanced Analytics — Technical Design

Status: **Direview.** Keputusan review di **Addendum B (akhir dokumen) mengikat dan menggantikan
bagian terkait** bila bertentangan. Belum ada implementasi.
Basis kode yang diinspeksi: commit `796e1e0` (branch `claude/review-engineering-rules-fu16n6`),
schema Room versi 1. Acuan: `CLAUDE.md`, `docs/ENGINEERING_SPEC.md`.

---

## 1. Executive summary

Tab **Analisis** akan menjadi halaman analitik deterministik dengan lima sub-tab:
**Wawasan · Tren · Kategori · Merchant · Pola**. Semua angka berasal dari Room, dihitung dengan
formula yang didefinisikan di dokumen ini, dan diuji terpisah dari UI.

Temuan audit paling penting yang membentuk desain ini:

1. **minSdk 29 = SQLite 3.22.** Window function (`ROW_NUMBER`, `NTILE`, `OVER(...)`) baru ada
   di SQLite 3.25. Terbukti: query dengan window function **gagal (syntax error)** pada
   SQLite 3.21, sedangkan Robolectric/JVM memakai SQLite modern sehingga test biasa *tidak akan
   menangkap* kesalahan ini. Percentile tidak boleh memakai window function.
2. **Percentile exact tanpa memuat semua data ke memori** dapat dilakukan dengan satu query
   `ORDER BY amount` yang di-*stream* lewat API Room 2.8 `useReaderConnection { usePrepared { step() } }`
   (terverifikasi ada di Room 2.8.5). Memori O(1) untuk percentile global, O(ukuran grup) per merchant.
3. **Jam transaksi tidak dapat dipercaya untuk analisis perilaku.** Form transaksi menyimpan jam
   saat form dibuka (`TransactionFormViewModel` baris 94 `clock.instant()`, baris 171 mengganti
   tanggal tetapi mempertahankan jam). Analisis per jam **ditunda**; analisis per hari dalam
   minggu tetap valid karena tanggal dipilih pengguna.
4. **Isu index `deletedAt` pada query Fase 2 bukan bottleneck utama.** EXPLAIN QUERY PLAN dan
   pengukuran menunjukkan masalah sebenarnya pada skala ekstrem adalah memuat seluruh riwayat
   ke memori (tanpa paging). Workaround `+deletedAt` justru **lebih lambat** untuk riwayat penuh.
   Dikategorikan backlog, bukan prasyarat Fase 4.
5. **Tidak perlu perubahan schema dan tidak perlu dependency baru.** Covering index terbukti
   mempercepat agregasi 1 tahun 4–6× pada 1 juta transaksi, tetapi pada skala realistis semua query
   sudah < 35 ms; index tersebut dicatat sebagai opsi terukur di masa depan.

Rekomendasi urutan: **4.0 Fondasi → 4.1 Tren → 4.2 Kategori → 4.3 Wawasan (aturan inti) →
4.4 Pola hari → 4.5 Distribusi → 4.6 Merchant**. Analisis per jam, deteksi anomali harian,
fuzzy matching merchant, dan covering index **ditunda**.

---

## 2. Current architecture audit

Diverifikasi langsung dari repository (bukan dari laporan fase sebelumnya).

| Area | Kondisi aktual | Catatan untuk Fase 4 |
|---|---|---|
| Modul | Satu modul `:app`, package `core`, `data`, `domain`, `analytics`, `presentation` | Tetap. Analitik baru masuk `analytics/` (murni Kotlin). |
| Analitik yang ada | `analytics/period/ReportPeriod`, `metrics/{CashFlow, PeriodComparison, ExpenseBreakdown, WalletTotals}`, `trends/DailySeries` | Dipakai ulang; `ComparisonSpans` perlu digeneralisasi (sekarang hanya bulan). |
| Data agregasi | `ReportDao` + `ReportRepository` (Fase 3) | Ditambah DAO terpisah `AnalyticsDao` agar `ReportDao` tidak membengkak. |
| Navigasi | `Routes.ANALYTICS` → `UnavailableScreen` | Diganti `AnalysisScreen`. |
| DI | Hilt, `DatabaseModule`, `TimeModule` (Clock UTC) | Tambah provider DAO baru saja. |
| Chart | Vico 3.3.1 (`compose-m3`), warna `LocalChartColors` | Cukup; tidak perlu library lain. |
| DataStore | **Belum ada di dependency** (berbeda dari konteks proyek yang menyebut DataStore sudah dipakai) | Fase 4 tidak membutuhkan preference persisten → tidak ditambahkan. |
| SDK | minSdk 29, compileSdk 37.2, targetSdk 36 | minSdk 29 menentukan batas fitur SQL (lihat §3). |
| Test | 195 test (JUnit, Robolectric, Compose UI v2, native graphics) | Infrastruktur dipakai ulang. |
| Celah terhadap master spec | Riwayat transaksi belum punya filter **per kategori/dompet** (Fase 2 hanya pencarian + periode) | Relevan untuk drill-down dari Analisis; lihat keputusan D3 di §17. |

---

## 3. Current database and query audit

### 3.1 Metode

Audit dilakukan sepenuhnya di scratchpad, **tanpa menyentuh repository**:

- Schema diambil persis dari `app/schemas/.../1.json` (18 statement, 14 index).
- Data sintetis **1.000.000 transaksi** selama 3 tahun (70% expense, 20% income, 10% transfer,
  1% soft-deleted, 0,5% bertanggal masa depan, 300 merchant dengan variasi penulisan) dan dataset
  realistis **36.000 transaksi** (≈33/hari, pengguna sangat aktif).
- Dijalankan pada **SQLite 3.21.0** (batas bawah API 29; 3.22.0 tidak tersedia di Maven, 3.21 lebih
  tua sehingga query yang lolos di 3.21 pasti lolos di 3.22) dan **SQLite 3.46.1** (Android modern),
  melalui `sqlite-jdbc`. Tanpa `ANALYZE` (Room tidak menjalankannya).
- Waktu = median 3 eksekusi di CPU desktop; perangkat Android kelas menengah diperkirakan 2–5×
  lebih lambat. Angka dipakai untuk **perbandingan relatif**, bukan janji absolut.

### 3.2 Hasil (SQLite 3.21; 3.46 konsisten ±15%)

| Query | Plan (ringkas) | 1 jt baris | 36 rb baris |
|---|---|---|---|
| P2 `observeAll` (saat ini) | index `deletedAt` + temp B-tree sort | 1063 ms, 990k baris | 33 ms |
| P2 `observeAll` dengan `+deletedAt` | scan index `transactionDate` | **2206 ms** (lebih lambat) | 49 ms |
| P2 `observeFiltered` rentang 1 bulan (saat ini) | index `deletedAt` | 119 ms | 3,8 ms |
| P2 `observeFiltered` + `+deletedAt` | scan index `transactionDate` (bukan range) | 87 ms | 2,5 ms |
| P2 filter bulan dengan predikat eksplisit `date >= ? AND date < ?` | **range** index `transactionDate` | 49 ms | 1,2 ms |
| P2 `observeByWallet` (saat ini) | index `deletedAt` | 365 ms, 266k baris | 11 ms |
| P2 `observeByWallet` + `+deletedAt` | OR atas index `walletId` + `destinationWalletId` | 359 ms | — |
| P1 saldo dompet (saat ini) | subquery 2 memakai index `type` (bukan `destinationWalletId`) | 623 ms | 17 ms |
| P1 saldo dompet + `+t.type` | subquery 2 memakai `destinationWalletId` | 503 ms | — |
| P3 total 1 bulan / 1 tahun | range `transactionDate` | 35 / 535 ms | 0,8 / 9,8 ms |
| P3 harian / kategori / recent (1 bulan) | range `(type, transactionDate)` | 32 / 25 / 0,1 ms | < 1 ms |

**Kesimpulan audit query lama:**

- Query Fase 3 sudah memakai index rentang yang benar di kedua versi SQLite.
- Masalah Fase 2 (`observeAll`, `observeByWallet`) adalah **memuat seluruh hasil ke memori**.
  Pada skala realistis (36 rb) hanya ≤ 33 ms. `+deletedAt` bukan solusi universal (memperlambat
  riwayat penuh 2×). Solusi yang benar: paging/limit dan pemisahan query berfilter rentang.
- Pola `(:x IS NULL OR transactionDate >= :x)` mencegah range scan. Pada filter bulanan, query
  dengan predikat eksplisit 2,4× lebih cepat.
- Query saldo dompet: subquery transfer masuk memakai index yang kurang tepat; perbaikan `+t.type`
  hanya ±20% karena biaya didominasi transaksi keluar per dompet. Prioritas rendah.

### 3.3 Kandidat query Fase 4 (diukur)

| Kandidat | Plan | 1 jt (1 tahun ≈ 229k expense) | 36 rb |
|---|---|---|---|
| Total harian 1 tahun (sumber tren) | range `(type, date)` | 510 ms, 365 baris | 12 ms |
| Kategori × hari 1 tahun | range `(type, date)` | 476 ms, 6.554 baris | 12 ms |
| Merchant agregat 1 bulan / 1 tahun | range `(type, date)` | 28 / 447 ms | 0,5 / 9 ms |
| Merchant first-seen **seluruh riwayat** | range `(type, date<?)` | **1246 ms** | 24 ms |
| Amount terurut 1 bulan / 1 tahun (stream percentile) | range + sort | 26 / 352 ms | 0,6 / 8 ms |
| Satu percentile via `LIMIT 1 OFFSET k` 1 tahun | range + sort | 478 ms **per percentile** | 10 ms |
| Window function | — | **ERROR di 3.21** | ERROR |
| Hari-dalam-minggu × jam (aritmetika WIB) | range `(type, date)` | 374 ms, 168 baris | 9 ms |

Eksperimen covering index `(type, transactionDate, deletedAt, amount, categoryId)` pada **salinan**
database: agregasi 1 tahun 510→97 ms, kategori×hari 476→146 ms, amount terurut 404→66 ms;
ukuran DB +15% (211→242 MB pada 1 jt baris) dan biaya tulis bertambah. Butuh schema v2 + migrasi.
**Tidak direkomendasikan untuk Fase 4** (skala realistis sudah cepat); dicatat sebagai opsi
terukur bila pengujian perangkat menunjukkan kebutuhan.

### 3.4 Validasi aritmetika zona waktu

Untuk hari-dalam-minggu dan pengelompokan harian di SQL dipakai offset tetap UTC+7:

- `hariDalamMinggu = ((transactionDate + 25 200 000) / 86 400 000 + 3) % 7` (Senin = 0 … Minggu = 6;
  1970-01-01 adalah Kamis = 3).
- Diverifikasi terhadap `zoneinfo` Asia/Jakarta pada 200.000 timestamp acak 1970–2100:
  **0 selisih**. Asia/Jakarta tidak memiliki transisi offset dari 1964 sampai 2099 (tzdata saat ini).
- Tetap dijaga dengan pemeriksaan runtime seperti Fase 3 (gagal keras bila suatu hari ≠ 24 jam
  pada rentang yang diminta) dan unit test atas aturan zona.

---

## 4. Proposed feature breakdown

Skor 1 (rendah) – 3 (tinggi). "Risiko" = risiko kesalahan finansial/statistik.

| Modul | Nilai bisnis | Kompleksitas | Dampak performa | Kompleksitas test | Risiko | Keputusan |
|---|---|---|---|---|---|---|
| A. Tren arus kas (harian/mingguan/bulanan) | 3 | 2 | 1 | 2 | 2 | **Masuk (4.1)** |
| B. Analisis kategori + hierarki | 3 | 2 | 1 | 2 | 2 | **Masuk (4.2)** |
| F. Wawasan deterministik (I1, I2, I3, I5) | 3 | 2 | 1 | 3 | 3 | **Masuk (4.3, bertahap)** |
| D. Pola hari dalam minggu, weekday vs weekend | 2 | 1 | 1 | 2 | 2 | **Masuk (4.4)** |
| D. Pola per jam | 2 | 1 | 1 | 1 | **3 (data jam tidak valid)** | **Ditunda** |
| E. Distribusi nominal (exact percentile) | 2 | 3 | 2 | 3 | 2 | **Masuk (4.5)** |
| C. Merchant (ranking, median, baru, perubahan) | 2 | 3 | 2 | 3 | 3 (kualitas data) | **Masuk (4.6), paling akhir** |
| F. Insight "pengeluaran harian di atas baseline" (I4) | 2 | 3 | 1 | 3 | 3 | **Ditunda** (lihat §13) |
| Fuzzy matching merchant | 1 | 3 | 2 | 3 | 3 | **Tidak dikerjakan** |

---

## 5. Mathematical definitions

Notasi: transaksi *t* memiliki `type`, `amount` (Long rupiah > 0), `date` (UTC ms), `deletedAt`.
Himpunan dasar untuk periode laporan *P*:

```
T(P) = { t | t.deletedAt IS NULL  ∧  start(P) ≤ t.date < endReported(P) }
endReported(P) = awal hari WIB setelah min(akhir P, hari ini WIB)
```

Semua batas adalah tengah malam Asia/Jakarta yang dikonversi ke UTC (`JakartaTime`). Transaksi
bertanggal masa depan tidak pernah masuk laporan. Transaksi dari **dompet yang diarsipkan tetap
masuk** analitik (analitik berbasis transaksi, bukan dompet aktif). Kategori yang diarsipkan tetap
muncul dengan namanya.

### 5.1 Arus kas (A)

| Metrik | Definisi | Tak terdefinisi bila |
|---|---|---|
| Pemasukan I(P) | Σ amount, type = INCOME | — (0 bila kosong) |
| Pengeluaran E(P) | Σ amount, type = EXPENSE | — |
| Arus kas bersih N(P) | I(P) − E(P) | — (boleh negatif) |
| Rasio tabungan S(P) | (I − E) / I × 100 | I(P) = 0 |
| Rata-rata pengeluaran harian Ē(P) | E(P) / d(P), d = jumlah **hari kalender yang sudah dilaporkan** (termasuk hari tanpa transaksi) | d(P) = 0 |
| Rata-rata per transaksi pengeluaran | E(P) / n_E(P) | n_E = 0 |

TRANSFER tidak pernah masuk I atau E.

**Bucket waktu.**

- Harian: hari kalender WIB.
- Mingguan: minggu ISO-8601 **Senin–Minggu** WIB (keputusan D1). Minggu yang terpotong batas
  periode ditandai `isPartial`.
- Bulanan: bulan kalender WIB.

Setiap bucket membawa `daysReported` dan `daysInBucket`; bucket dengan
`daysReported < daysInBucket` adalah **parsial**: tetap ditampilkan dengan penanda "berjalan" /
"sebagian", tetapi **dikecualikan dari rata-rata antar-bucket** dan dari perbandingan, kecuali
dibandingkan dengan rentang ekuivalen.

**Perbandingan periode yang adil** (generalisasi `ComparisonSpans` Fase 3):

- Bulanan berjalan: hari 1..D bulan ini vs hari 1..min(D, panjang bulan lalu) bulan lalu
  (sudah ada).
- Mingguan berjalan: Senin..hari-ini minggu ini vs hari yang sama minggu lalu.
- Bucket lengkap: bucket penuh vs bucket penuh sebelumnya.
- Rentang kustom sepanjang L hari: vs L hari tepat sebelumnya.

**Perubahan** (sama dengan Fase 3): Δabs = cur − prev; Δ% = (cur − prev) / prev × 100 **hanya
bila prev > 0**. prev = 0 → "tidak tersedia (basis nol)"; prev < 0 (mungkin untuk N) → "tidak
ditampilkan (basis negatif)". Rasio tabungan dibandingkan dalam **poin persentase**
(S_cur − S_prev), bukan persen dari persen.

### 5.2 Kategori (B)

- `E_c(P)` = Σ amount, type = EXPENSE, categoryId = c. Identitas = **categoryId**, nama hanya label.
  categoryId NULL membentuk bucket sendiri "Tanpa kategori".
- Kontribusi `share_c = E_c / E × 100` (tidak ada bila E = 0).
- **Agregasi hierarkis** (keputusan: ya, dua tampilan):
  - *Per kategori*: datar berdasarkan id (seperti dashboard).
  - *Per kategori induk*: `E_root(r) = E_r + Σ_{c: parent(c)=r} E_c`. Hierarki maksimal 2 level
    (dijamin validator Fase 1), sehingga roll-up cukup satu langkah di Kotlin. Anak dengan induk
    yang diarsipkan tetap digulung ke id induknya (induk masih ada di tabel).
  - Total kedua tampilan **harus sama** dengan E(P) (invarian yang diuji).
- Perubahan per kategori memakai rentang ekuivalen §5.1.
- **Kenaikan/penurunan terbesar** diurutkan berdasarkan **Δabs** (bukan Δ%), karena persentase
  pada basis kecil menyesatkan. Δ% ditampilkan hanya bila prev ≥ Rp100.000 (ambang M_c, keputusan D2).
- **Tren bulanan per kategori**: E_c per bulan untuk N bulan lengkap + bulan berjalan (parsial).

### 5.3 Merchant (C)

- **Kunci normalisasi** `key(m) = lowercase(Locale.ROOT, collapseWhitespace(trim(m)))`. NULL atau
  kosong setelah trim → bucket "Tanpa merchant" (dihitung dalam total, dikecualikan dari ranking,
  porsinya ditampilkan sebagai "cakupan data merchant").
- Tidak ada fuzzy matching. "Indomaret" dan "Indomaret Point" adalah merchant berbeda.
- Nama tampilan = varian mentah yang paling sering muncul (tie → urutan leksikografis).
- Per merchant k: `n_k`, `Σ_k`, `mean_k = Σ_k / n_k`, `median_k` (definisi §5.5), `share_k = Σ_k / E`.
- **Merchant baru di P**: k muncul di T(P) dan **tidak** muncul pada lookback 365 hari sebelum
  start(P). Lookback dibatasi agar query tidak memindai seluruh riwayat (terukur 1,2 s pada 1 jt baris).
  Label UI: "baru dalam 12 bulan terakhir".
- **Kenaikan frekuensi/nominal**: Δn dan ΔΣ terhadap rentang ekuivalen; hanya bila n_prev ≥ 3.

### 5.4 Pola waktu (D)

- `dow(t)` = hari WIB (Senin = 0). Untuk P: `E_dow`, `n_dow`.
- **Normalisasi kalender (wajib):** dalam 30 hari, sebagian hari muncul 5×, sebagian 4×.
  `occ_dow(P)` = jumlah tanggal dengan hari tersebut di P (hari yang sudah dilaporkan).
  Rata-rata pengeluaran per hari-kalender `Ē_dow = E_dow / occ_dow`.
- Weekday vs weekend: `Ē_weekend = (E_Sab + E_Min) / (occ_Sab + occ_Min)`; `Ē_weekday` analog.
- Rata-rata nominal per transaksi `E_dow / n_dow` (bila n_dow > 0).
- **Per jam: ditunda.** Data jam saat ini = jam pencatatan, bukan jam transaksi (§1 poin 3).

### 5.5 Distribusi nominal (E)

Untuk himpunan nominal pengeluaran `x₁ ≤ x₂ ≤ … ≤ xₙ` di T(P):

| Statistik | Definisi | Minimum n |
|---|---|---|
| count, sum | n, Σx (Long, `Math.addExact`) | 1 |
| min, max | x₁, xₙ | 1 |
| mean | Σx / n (dihitung dari Long, hasil Double) | 1 |
| Percentile p (P25, P50, P75, P90, P95, P99) | **Linear interpolation, Hyndman–Fan type 7** (= Excel `PERCENTILE.INC`, NumPy default): h = (n − 1)·p; Q = x₍⌊h⌋+1₎ + (h − ⌊h⌋)(x₍⌊h⌋+2₎ − x₍⌊h⌋+1₎) | P50: 1 · P25/P75/IQR: 4 · P90: 10 · P95: 20 · P99: 100 |
| IQR | P75 − P25 | 4 |
| Simpangan baku | **Populasi**: σ = √(Σ(x − mean)² / n), dihitung dengan **Welford** (stabil numerik) | 2 |

Alasan pilihan:

- **Type 7** adalah definisi paling umum dan median-nya sama dengan definisi buku teks (rata-rata
  dua nilai tengah). Hasil Double dibulatkan *half-up* ke rupiah hanya untuk tampilan.
- **σ populasi**, karena data yang dideskripsikan adalah *seluruh* transaksi pengguna di P, bukan
  sampel dari populasi yang lebih besar. Jika kelak dipakai untuk inferensi atau z-score
  (deteksi anomali), pakai simpangan baku sampel (n − 1); ini dicatat agar tidak tertukar.
- **Exact, bukan approximate.** Exact layak karena satu sort ter-stream sudah ≤ 352 ms pada skenario
  ekstrem 229k baris, dan ≤ 8 ms pada skala realistis. Approximate (histogram/t-digest) ditolak:
  error-nya sulit dijelaskan ke pengguna, dan perlu dependency atau kode kompleks.
- Ambang minimum mencegah percentile ekor seperti "P99 dari 7 transaksi" yang tidak bermakna.

### 5.6 Wawasan deterministik (F)

Setiap insight adalah fungsi murni `(agregat) → Insight?` dengan **formula**, **syarat data
minimum**, **ambang**, dan **bukti angka** yang ikut ditampilkan. Bahasa memakai kata
"cenderung", "terindikasi", atau "dibanding". Ambang adalah konstanta bernama di satu file dan
diuji pada nilai batas (tepat di bawah atau di atas ambang).

| ID | Aturan | Data minimum | Ambang | Contoh teks |
|---|---|---|---|---|
| I1 | Kenaikan pengeluaran kategori vs rentang ekuivalen | prev_c ≥ Rp100.000; n_c(cur) ≥ 3 | Δ% ≥ +30% **dan** Δabs ≥ Rp200.000; maks 3 kategori, urut Δabs | "Pengeluaran Makanan naik 45% (+Rp600.000) dibanding 1–25 Okt 2026." |
| I2 | Weekend vs weekday | ≥ 4 hari weekend dan ≥ 10 hari weekday yang dilaporkan; ≥ 20 transaksi pengeluaran | Ē_weekend / Ē_weekday ≥ 1,5 (atau ≤ 1/1,5 untuk kebalikannya) | "Rata-rata pengeluaran harian akhir pekan cenderung 1,8× hari kerja." |
| I3 | Konsentrasi merchant | cakupan merchant ≥ 50% dari E; n_k ≥ 3 | share_k ≥ 30% dari E | "Toko X menyerap 34% pengeluaran periode ini (5 transaksi)." |
| I5 | Penurunan rasio tabungan | I_cur > 0, I_prev > 0, I_prev ≥ Rp1.000.000 | S_cur − S_prev ≤ −10 poin | "Rasio tabungan turun 12 poin (dari 40% menjadi 28%)." |

Insight tidak ditampilkan bila syaratnya tidak terpenuhi. Tab Wawasan menampilkan alasan
eksplisit, misalnya "Belum cukup data: minimal 20 transaksi pengeluaran, saat ini 7".

---

## 6. Proposed SQL queries and data flow

Aturan untuk semua query baru:

- `+deletedAt IS NULL` (alasan dan bukti di §3; dipertahankan seperti Fase 3).
- Rentang eksplisit `transactionDate >= :start AND transactionDate < :end`. Jangan memakai pola
  `(:x IS NULL OR …)`.
- Filter `type` eksplisit.
- **Tanpa window function, `FILTER`, `NULLS FIRST/LAST`, atau upsert**, karena tidak tersedia di
  SQLite 3.22.

### Q1 — Total harian (perluasan `ReportDao.observeDailyTotals`)

- Tujuan: sumber tren harian, mingguan, dan bulanan. Agregasi minggu dan bulan dilakukan di Kotlin
  dari ≤ 366 baris per tahun, sehingga satu scan melayani tiga granularitas.
- Input: `start`, `end`, `dayMillis`.
- Output: `dayIndex, income, expense, incomeCount, expenseCount`. Kolom count ditambahkan; query
  dashboard tetap kompatibel.
- Filter: `type IN ('INCOME','EXPENSE')`.
- Index: `(type, transactionDate)` range, sudah terverifikasi.
- Bottleneck: 510 ms pada 1 tahun / 1 jt baris (ekstrem); 12 ms realistis. Rentang maksimal 12 bulan.
- Uji performa: plan test + dataset besar di Robolectric (§12).

### Q2 — Pengeluaran kategori × hari

```sql
SELECT categoryId, (transactionDate - :start) / :dayMillis AS dayIndex,
       SUM(amount) AS total, COUNT(*) AS cnt
FROM transactions
WHERE +deletedAt IS NULL AND type = 'EXPENSE'
  AND transactionDate >= :start AND transactionDate < :end
GROUP BY categoryId, dayIndex
```

- Tujuan: total kategori untuk periode, rentang ekuivalen, dan tren bulanan dalam **satu** scan.
  Roll-up ke bulan, periode, dan induk dilakukan di Kotlin.
- Output: ≤ hari × kategori baris (6.554 baris untuk 1 tahun / 18 kategori).
- Index: `(type, transactionDate)`.
- Bottleneck: 476 ms pada skala ekstrem; 12 ms realistis.

### Q3 — Agregat merchant mentah

```sql
SELECT merchant, COUNT(*) AS cnt, SUM(amount) AS total
FROM transactions
WHERE +deletedAt IS NULL AND type = 'EXPENSE'
  AND transactionDate >= :start AND transactionDate < :end
GROUP BY merchant
```

- Normalisasi dilakukan di Kotlin agar Unicode-aware dan bisa menggabungkan spasi ganda. SQLite
  `lower()` hanya ASCII dan tidak punya regex. Varian mentah biasanya hanya ratusan baris, sehingga
  penggabungan di Kotlin murah.
- NULL dan kosong menghasilkan bucket "Tanpa merchant".
- Index: `(type, transactionDate)`.

### Q4 — Merchant terakhir pada lookback

```sql
SELECT DISTINCT merchant
FROM transactions
WHERE +deletedAt IS NULL AND type = 'EXPENSE' AND merchant IS NOT NULL
  AND transactionDate >= :lookbackStart AND transactionDate < :periodStart
```

- Tujuan: menentukan merchant baru. Lookback 365 hari, jadi tidak memindai seluruh riwayat.

### Q5 — Hari dalam minggu

```sql
SELECT ((transactionDate + 25200000) / 86400000 + 3) % 7 AS dow,
       COUNT(*) AS cnt, SUM(amount) AS total
FROM transactions
WHERE +deletedAt IS NULL AND type = 'EXPENSE'
  AND transactionDate >= :start AND transactionDate < :end
GROUP BY dow
```

- Konstanta offset berasal dari satu tempat (`JakartaTime`), dan zona dijaga oleh pemeriksaan
  runtime serta unit test. `occ_dow` dihitung di Kotlin dari kalender.

### S1 — Stream nominal terurut (distribusi)

```sql
SELECT amount
FROM transactions
WHERE +deletedAt IS NULL AND type = 'EXPENSE'
  AND transactionDate >= ? AND transactionDate < ?
ORDER BY amount
```

- Dijalankan via `RoomDatabase.useReaderConnection { conn -> conn.usePrepared(sql) { stmt -> while (stmt.step()) … } }`.
- Dalam koneksi baca yang sama, `COUNT(*)` dijalankan lebih dulu untuk menentukan rank yang
  dibutuhkan. Lalu satu pass dilakukan sambil memilih nilai pada rank tersebut dan menghitung
  Welford. Memori O(jumlah percentile).
- Reaktivitas: dipicu ulang oleh `InvalidationTracker.createFlow("transactions")` dan perubahan
  periode.
- Sort sementara SQLite: 352 ms pada 229k baris, 8 ms realistis.
- **Dibandingkan alternatif:** `LIMIT 1 OFFSET k` butuh satu sort per percentile (6×), dan window
  function tidak tersedia di API 29.

### S2 — Stream (merchant, amount) terurut untuk median per merchant

- `ORDER BY merchant, amount`. Kotlin menggabungkan varian mentah ke kunci normalisasi. Varian
  berbeda dari kunci yang sama bisa tidak bersebelahan, sehingga nilai dikumpulkan per kunci.
- Memori O(jumlah transaksi berpetak merchant dalam P). Karena itu **rentang analisis merchant
  dibatasi maksimal 12 bulan**.

### Data flow

```
Room (SQL agregasi Q1–Q5 / stream S1–S2)
  → AnalyticsRepository (Flow; stream dibungkus flow dari InvalidationTracker)
  → analytics/* (fungsi murni: bucket, roll-up, statistik, aturan insight)
  → AnalysisViewModel (StateFlow per tab; hanya tab aktif yang mengumpulkan data)
  → Composable (hanya format & tampilkan)
```

Tidak ada N+1: label kategori atau dompet diambil sekali dari flow `observeAll*` yang sudah ada,
lalu di-join di Kotlin.

---

## 7. Proposed domain models (pure Kotlin, `analytics/`)

```kotlin
// analytics/trends
enum class Granularity { DAILY, WEEKLY, MONTHLY }
data class TrendBucket(
    val first: LocalDate, val last: LocalDate,           // inclusive, WIB
    val daysInBucket: Int, val daysReported: Int,
    val income: Long, val expense: Long, val incomeCount: Int, val expenseCount: Int,
) { val isPartial get() = daysReported < daysInBucket; val net get() = income - expense }
object TrendBuckets { fun rollUp(daily: List<DailyTotals>, granularity: Granularity, span: DateSpan): List<TrendBucket> }

// analytics/metrics
data class CategoryTotal(val categoryId: String?, val total: Long, val count: Int)
data class CategoryNode(val categoryId: String?, val direct: Long, val children: List<CategoryTotal>) { val rolledUp: Long }
object CategoryAnalysis { fun flat(...); fun byParent(totals, categoriesById): List<CategoryNode>; fun movers(cur, prev, minBaseline): List<CategoryChange> }
@JvmInline value class MerchantKey(val value: String) { companion object { fun of(raw: String?): MerchantKey? } }
data class MerchantStats(val key: MerchantKey, val displayName: String, val count: Int, val total: Long, val median: Double?)
data class WeekdayPattern(val byDay: List<WeekdayTotal>, val weekendDailyAverage: Double?, val weekdayDailyAverage: Double?)

// analytics/stats
data class DistributionSummary(
    val count: Int, val sum: Long, val min: Long?, val max: Long?, val mean: Double?,
    val percentiles: Map<Percentile, Double?>,  // null when below minimum n
    val iqr: Double?, val populationStdDev: Double?,
)
enum class Percentile(val p: Double, val minCount: Int) { P25(.25, 4), P50(.5, 1), P75(.75, 4), P90(.9, 10), P95(.95, 20), P99(.99, 100) }
class StreamingDistribution(count: Int) { fun accept(sortedValue: Long); fun result(): DistributionSummary }  // type 7 + Welford

// analytics/insights
sealed interface Insight { val evidence: InsightEvidence
    data class CategoryIncrease(...) : Insight; data class WeekendSpending(...) : Insight
    data class MerchantConcentration(...) : Insight; data class SavingRateDrop(...) : Insight }
sealed interface InsightUnavailable { data class NotEnoughTransactions(val required: Int, val actual: Int) : InsightUnavailable; ... }
object InsightRules { /* thresholds as named constants */ fun evaluate(input: InsightInput): InsightResult }
```

---

## 8. Proposed repository structure

| File | Isi |
|---|---|
| `data/local/dao/AnalyticsDao.kt` (baru) | Q2–Q5 (`@Query`, Flow). |
| `data/local/dao/ReportDao.kt` (ubah) | Q1 menambah kolom count. Satu sumber query harian untuk dashboard dan tren. |
| `data/local/AmountStreams.kt` (baru) | S1/S2 via `useReaderConnection`. Satu-satunya tempat SQL mentah non-`@Query`, beserta test-nya. |
| `data/repository/AnalyticsRepository.kt` (baru) | Membungkus DAO + stream menjadi Flow domain; memverifikasi hari 24 jam; membatasi rentang (12 bulan). |
| `core/util/JakartaTime.kt` (ubah) | Konstanta offset tetap terverifikasi, minggu ISO, `todayFlow(clock)` yang berdetak saat tengah malam WIB. |

`FinTrackDatabase` hanya bertambah `abstract fun analyticsDao()`. **Schema tetap versi 1.**
DAO baru tidak mengubah identity hash schema, dan ini diverifikasi oleh `MigrationTest` yang sudah
ada.

---

## 9. Proposed ViewModel state

Satu `AnalysisViewModel` untuk halaman. Periode dan tab disimpan di `StateFlow`, dan hanya tab
aktif yang mengumpulkan datanya (`flatMapLatest` pada tab), sehingga query tab lain tidak berjalan.

```kotlin
enum class AnalysisTab { INSIGHTS, TREND, CATEGORY, MERCHANT, PATTERN }
sealed interface AnalysisPeriod { ThisMonth; LastMonth; LastMonths(n: 3|6|12); Custom(first, last) }

data class AnalysisState(
    val tab: AnalysisTab, val period: AnalysisPeriod, val resolved: ResolvedPeriod,
    val content: TabContent,            // Loading | Error | NotStarted | Ready(per-tab data)
)
sealed interface TabContent {
    data object Loading : TabContent
    data object LoadFailed : TabContent
    data class Ready<T>(val data: T) : TabContent
}
// Ready payloads: InsightsData, TrendData(granularity, buckets, comparison), CategoryData(view: FLAT|PARENT, rows, movers),
// MerchantData(rows, coverage, newMerchants), PatternData(weekday, distribution)
```

- "Hari ini" berasal dari `todayFlow` yang memancarkan ulang saat tengah malam WIB. Ini memperbaiki
  backlog Fase 3 untuk Beranda maupun Analisis.
- Period dan state dibangun dari sumber yang sama untuk mencegah race seperti yang ditemukan di
  Fase 3: periode yang dilaporkan ikut di dalam payload, bukan dari flow terpisah.

---

## 10. UI/UX page structure

**Header:** judul "Analisis", lalu chip periode (Bulan ini · Bulan lalu · 3 bln · 6 bln · 12 bln ·
Pilih tanggal) dan label rentang yang dilaporkan, misalnya "Sep 2026 – 25 Nov 2026 · berjalan".
Di bawahnya ada `PrimaryScrollableTabRow` dengan lima tab. Tab baru muncul **saat sub-fasenya
selesai**, jadi tidak ada tab kosong yang berpura-pura aktif.

| Tab | Isi (urutan) | Chart |
|---|---|---|
| **Wawasan** | Kartu insight (maks 5) dengan bukti angka & dasar perhitungan yang dapat diperluas; daftar "belum cukup data" dengan alasan. | — |
| **Tren** | Toggle Harian/Mingguan/Bulanan → ringkasan (I, E, N, S, Ē) → chart kolom I vs E per bucket (bucket parsial bergaris/pudar + label) → tabel perbandingan ekuivalen. | Vico column (sudah ada), marker untuk nilai |
| **Kategori** | Toggle Per kategori / Per induk → ranking dengan bar proporsional (Compose `Box` lebar relatif, bukan chart dekoratif) + share + Δ → "Naik/turun terbesar" → tren bulanan kategori terpilih. | Bar horizontal sederhana; Vico line untuk tren satu kategori |
| **Merchant** | Cakupan data merchant (%) → ranking by total / by frekuensi (toggle) dengan n, rata-rata, median → merchant baru (12 bln) → perubahan terbesar. | Tanpa chart (daftar lebih jelas) |
| **Pola** | Hari dalam minggu (Ē_dow ter-normalisasi + n) → weekday vs weekend → **Distribusi nominal** (tabel count/sum/min/P25/median/P75/P90/P95/P99/max/mean/σ/IQR dengan "—" + alasan bila n kurang). | Vico column 7 batang |

State:

- **Loading:** indikator per tab.
- **Error:** pesan + petunjuk.
- **Empty:** "Belum ada transaksi pada periode ini".
- **No-data per modul:** alasan eksplisit, misalnya "Data merchant hanya tersedia pada 12% pengeluaran".
- **Periode belum dimulai:** sama seperti Fase 3.

Dark/light memakai `LocalChartColors`. Semua teks dalam Bahasa Indonesia, angka memakai
`formatRupiah`/`formatPercent`, dan tanggal memakai `JakartaTime`.

Navigasi drill-down (ketuk kategori/merchant → riwayat terfilter) **bergantung keputusan D3**.
Tanpa keputusan itu, item ranking **tidak dibuat bisa diketuk**, supaya tidak ada tombol tanpa fungsi.

---

## 11. Performance considerations

- **Skala target:** realistis ≤ 50.000 transaksi; desain tetap aman pada 1 juta (diuji di §3).
- Tidak ada query yang memuat seluruh transaksi ke memori. Agregasi dilakukan di SQL. Percentile
  memakai stream terurut dengan memori O(k).
- Satu scan per sumber data: tren harian dipakai untuk harian, mingguan, dan bulanan; kategori ×
  hari dipakai untuk periode, rentang ekuivalen, tren, dan hierarki.
- Hanya tab aktif yang menjalankan query.
- Rentang maksimal 12 bulan + 365 hari lookback untuk merchant baru. Rentang kustom di atas
  batas → pesan eksplisit.
- Query Flow Room dijalankan ulang pada setiap perubahan tabel `transactions`. Untuk stream
  S1/S2, `conflate()` + `debounce(150 ms)` mencegah pekerjaan berulang saat banyak penulisan berturut-turut.
- Overflow: SUM di SQLite melempar error bila melewati Long. Batas input (15 digit) membuat ini
  praktis mustahil. Roll-up di Kotlin memakai `Math.addExact`, dan error ditangani sebagai state
  error, bukan crash diam-diam.
- **Index baru: tidak ada.** Covering index adalah opsi terukur (§3.3) bila pengujian perangkat
  menunjukkan kebutuhan, dan memerlukan schema v2 + migrasi teruji.

---

## 12. Testing strategy

**Unit (JVM, murni):**

- **Statistik:**
  - Mean, median, P25–P99 type 7 dibandingkan nilai referensi NumPy yang dicantumkan di test.
  - n = 1, 2, 4, 10, 20, 100 (batas minimum).
  - Data identik (σ = 0).
  - Welford vs perhitungan dua-pass `BigDecimal` pada 100.000 nilai acak (toleransi 1e-9 relatif).
  - Monotonisitas min ≤ P25 ≤ … ≤ max (property test dengan seed tetap).
  - IQR.
- **Rasio:**
  - Rasio tabungan: I = 0 (tak terdefinisi) dan I < E (negatif).
  - Perbandingan poin persentase.
  - Δ% dengan basis nol dan basis negatif.
- **Bucket:**
  - Minggu ISO yang melintasi bulan dan tahun (29 Des 2025 – 4 Jan 2026).
  - Februari kabisat 2028.
  - Bucket parsial dikecualikan dari rata-rata.
  - Hari tanpa transaksi.
- **Kategori:**
  - Roll-up induk = jumlah langsung + anak.
  - Total datar = total hierarki = E.
  - Dua kategori bernama sama tetap terpisah.
  - Induk diarsipkan.
  - NULL category.
  - Movers diurutkan berdasarkan Δabs.
- **Merchant:**
  - Normalisasi: spasi depan/belakang/ganda, huruf besar, Unicode (mis. "É"), kosong → null.
  - Nama tampilan = varian terbanyak.
  - Median per kunci saat varian tidak bersebelahan.
  - Merchant baru dengan lookback.
- **Pola:**
  - `occ_dow` untuk bulan 28/30/31 hari.
  - Weekday vs weekend pada data seimbang dan tidak seimbang.
- **Insight:**
  - Setiap aturan diuji tepat di bawah ambang, tepat di ambang, dan di atas ambang.
  - Setiap syarat minimum.
  - Teks bukti berisi angka yang benar.
- **Zona waktu:** aturan Asia/Jakarta tanpa transisi 1970–2100; rumus `dow` SQL = `java.time`
  pada sampel acak.

**Repository/DAO (Robolectric + Room in-memory):**

- **Pengecualian data:** transfer tidak masuk I, E, kategori, merchant, distribusi, atau pola;
  soft delete dikecualikan.
- **Arsip:**
  - Transaksi dari dompet diarsipkan **tetap** masuk analitik.
  - Kategori diarsipkan tetap muncul dengan nama.
- **Batas waktu:**
  - Batas WIB 16:59:59Z / 17:00:00Z.
  - Transaksi masa depan dikecualikan.
  - Rentang kustom yang melewati hari ini dipotong.
- **Stream S1/S2:** hasil sama dengan sort di memori pada 5.000 nilai acak (seed tetap).
- **Plan test** (pola `ReportQueryPlanTest`): semua query baru memakai index rentang.
- **Dataset besar:** 50.000 baris disisipkan dalam satu transaksi. Hasil harus benar (dibandingkan
  agregasi Kotlin), dan waktu dicatat tanpa assertion ketat agar tidak flaky.
- **Kompatibilitas SQLite 3.22 (lihat D4):** jalankan string SQL baru pada SQLite 3.21 via
  `sqlite-jdbc` (testImplementation), atau minimal test yang menolak `OVER (`, `FILTER (`,
  `NULLS FIRST`, `ON CONFLICT … DO`.

**UI (Robolectric, Compose v2, native graphics):**

- Loading, empty, error, dan no-data per tab beserta alasannya.
- Pergantian periode memperbarui konten tab aktif.
- Pergantian tab hanya memuat tab tersebut.
- Chart dirender: pemeriksaan piksel warna income/expense seperti Fase 3, terang dan gelap.
- Format angka Indonesia: "Rp1.500.000", "12,5%", "+3,2 poin".
- Insight yang tidak memenuhi syarat tidak tampil.
- Bila D3 disetujui: navigasi drill-down membuka riwayat terfilter.

---

## 13. Risks and trade-offs

| Risiko | Dampak | Mitigasi |
|---|---|---|
| SQL modern lolos test JVM tetapi gagal di API 29 | Crash di perangkat lama | Larangan fitur > 3.22, test kompatibilitas (D4), uji perangkat API 29 |
| Jam transaksi = jam input | Insight perilaku per jam salah | Analisis per jam ditunda; bila nanti dibutuhkan, form harus menyediakan input jam (perubahan UX & data) |
| Data merchant opsional/jarang | Ranking menyesatkan | Tampilkan cakupan %, ambang I3 mensyaratkan cakupan ≥ 50% |
| Normalisasi merchant terlalu lemah/kuat | Merchant terpecah/tergabung keliru | Aturan sederhana & transparan, tanpa fuzzy; varian ditampilkan di detail (opsional) |
| Persentase basis kecil | Kesimpulan berlebihan | Urut Δabs; Δ% hanya bila basis ≥ ambang |
| Insight terdengar seperti nasihat pasti | Kepercayaan salah | Bahasa "cenderung/terindikasi", bukti angka, syarat minimum |
| Stream S2 memori pada rentang panjang | OOM di perangkat lemah | Batas 12 bulan; ukuran = transaksi bermerchant dalam P |
| Room re-query pada tiap penulisan | Kerja berulang | Hanya tab aktif; debounce stream |
| Double untuk statistik | Pembulatan | Basis tetap Long; Welford; tampilan dibulatkan; toleransi test eksplisit |
| Tidak ada verifikasi perangkat | Bug runtime tak terdeteksi | Lihat §15 (prasyarat 4.0) |
| Insight I4 (baseline harian) | Banyak hari nol → median 0, MAD 0 → aturan rapuh | **Ditunda**; butuh desain terpisah (mis. baseline hari berbelanja saja) dan persetujuan |

---

## 14. Dependencies

**Tidak ada dependency produksi baru.** Room 2.8.5 (stream), Vico 3.3.1 (chart), `java.time`
(kalender), dan Kotlin stdlib sudah cukup.

Opsional (keputusan D4): `org.xerial:sqlite-jdbc:3.21.0.1` sebagai **testImplementation saja**
untuk menguji string SQL terhadap SQLite yang setara API 29. Manfaatnya menutup celah yang tidak
bisa ditangkap Robolectric. Biayanya satu artefak test (~6,7 MB) dan SQL harus dideklarasikan
sebagai konstanta yang bisa diuji.

---

## 15. Implementation sequence

Klasifikasi backlog Fase 3 (bagian 8 permintaan):

| Temuan | Bukti | Klasifikasi |
|---|---|---|
| Tanggal dashboard tidak berganti setelah tengah malam | `DashboardViewModel` mengambil `today` hanya saat periode dipilih | **Bagian Fase 4 (4.0)**: Analisis butuh mekanisme yang sama (`todayFlow`) |
| Index `deletedAt` pada query Fase 2 | §3.2: index bukan bottleneck; masalahnya memuat seluruh hasil. `+deletedAt` memperlambat riwayat penuh 2× | **Backlog**: solusi yang benar adalah paging riwayat + pemisahan query rentang. Tidak dikerjakan tanpa desain paging |
| Belum ada verifikasi perangkat/emulator | Tiga fase belum pernah dijalankan di Android nyata; risiko SQLite 3.22 nyata | **Harus dilakukan sebelum Fase 4 dinyatakan selesai**, dan sangat disarankan sebelum 4.1: smoke test manual oleh pemilik proyek di emulator/perangkat **API 29** dan API terbaru. Container ini tidak punya KVM |
| Grafik Fase 3 tanpa tooltip | — | **Bagian Fase 4 (opsional 4.1)** sebagai komponen chart bersama (Vico `CartesianMarker`); bila waktu tidak cukup → backlog |
| (Baru) Query saldo memakai index `type` untuk transfer masuk | §3.2, +20% dengan `+t.type` | **Backlog** (prioritas rendah) |

Urutan sub-fase (masing-masing: implementasi → test → build/lint → review → laporan → persetujuan):

1. **4.0 Fondasi:** `todayFlow` (tengah malam WIB) untuk Beranda & Analisis; generalisasi rentang
   ekuivalen (minggu, rentang kustom); `TrendBuckets`; konstanta offset WIB terverifikasi;
   `AnalyticsDao` kosong + wiring; kerangka `AnalysisScreen` dengan header periode (**tanpa** tab
   yang belum berfungsi). Test kompatibilitas SQL (D4).
2. **4.1 Tren:** Q1 + bucket harian, mingguan, bulanan; ringkasan; perbandingan ekuivalen; chart.
3. **4.2 Kategori:** Q2; datar & hierarki; movers; tren bulanan per kategori.
4. **4.3 Wawasan inti:** I1 (kategori) dan I5 (rasio tabungan), yang hanya bergantung pada 4.1–4.2.
5. **4.4 Pola hari:** Q5; `occ_dow`; weekday vs weekend; insight I2.
6. **4.5 Distribusi:** S1 streaming exact; tabel statistik di tab Pola.
7. **4.6 Merchant:** Q3, Q4, S2; normalisasi; ranking; median; merchant baru; insight I3.

---

## 16. Acceptance criteria per sub-phase

Berlaku untuk semua sub-fase:

- `./gradlew clean assembleDebug testDebugUnitTest lintDebug` sukses dari keadaan bersih.
- Tidak ada lint error atau warning baru.
- Schema tetap versi 1.
- Tidak ada TODO/FIXME.
- Setiap formula di dokumen ini punya test yang memverifikasi nilai, bukan hanya tidak crash.

| Sub-fase | Kriteria khusus |
|---|---|
| 4.0 | Beranda berganti ke hari baru saat tengah malam WIB (test dengan clock yang dimajukan); rentang ekuivalen teruji untuk bulan, minggu, kustom; tab Analisis menampilkan header periode & state kosong yang jujur; tidak ada tab tanpa fungsi. |
| 4.1 | Total bucket harian = mingguan = bulanan = total periode (invarian); bucket parsial ditandai dan tidak masuk rata-rata; Ē memakai hari kalender termasuk hari nol; transfer/soft delete/masa depan dikecualikan; chart lulus pemeriksaan piksel terang/gelap. |
| 4.2 | Σ ranking datar = Σ hierarki = E(P); kategori diarsipkan & NULL tampil; movers urut Δabs; Δ% hanya di atas ambang basis. |
| 4.3 | I1/I5 muncul tepat pada ambang dan tidak muncul di bawahnya; setiap insight menampilkan bukti angka & dasar perhitungan; alasan "belum cukup data" tampil. |
| 4.4 | Ē_dow ter-normalisasi `occ_dow` (test bulan 28/30/31 hari); rumus SQL `dow` = `java.time` pada sampel acak; I2 sesuai ambang. |
| 4.5 | Percentile type 7 cocok dengan nilai referensi; stream = sort di memori pada data acak; ambang minimum n menghasilkan "—" + alasan; tidak ada pemuatan seluruh daftar ke memori. |
| 4.6 | Normalisasi sesuai §5.3; cakupan merchant tampil; merchant baru memakai lookback 365 hari; median per kunci benar saat varian tidak bersebelahan; I3 sesuai ambang. |

---

## 17. Sengaja tidak dikerjakan & keputusan yang dibutuhkan

**Tidak dikerjakan di Fase 4:**

- Analisis per jam (data jam tidak valid, §1).
- Insight I4 "pengeluaran harian di atas baseline" dan deteksi anomali z-score/MAD. Butuh desain
  terpisah untuk data harian yang jarang.
- Fuzzy matching merchant atau penggabungan merchant manual.
- Approximate percentile, histogram, t-digest.
- Perubahan schema / covering index / migrasi.
- Paging riwayat transaksi dan perbaikan query Fase 2 (backlog).
- AI/LLM/ML, budget, tujuan keuangan, impor CSV, sinkronisasi, ekspor laporan.
- DataStore (tidak ada preferensi yang perlu dipersistenkan).

**Keputusan yang dibutuhkan dari pemilik proyek:**

| ID | Pertanyaan | Rekomendasi |
|---|---|---|
| D1 | Awal minggu untuk bucket mingguan | Senin (ISO-8601) |
| D2 | Ambang default insight & Δ% (Rp100.000 basis kategori, +30%/Rp200.000 untuk I1, 1,5× untuk I2, 30% untuk I3, −10 poin untuk I5) | Setujui sebagai awal; semua di satu file konstanta |
| D3 | Drill-down dari ranking ke riwayat terfilter memerlukan filter kategori/merchant di layar Transaksi (celah terhadap master spec) | Tambahkan filter kategori di riwayat sebagai bagian 4.2; merchant via pencarian teks yang sudah ada |
| D4 | Test kompatibilitas SQLite 3.21 dengan `sqlite-jdbc` (testImplementation) | Ya; risiko API 29 tidak tertangkap oleh Robolectric |
| D5 | Periode default tab Analisis | "3 bulan terakhir" (cukup data untuk tren & insight) |

---

### Lampiran A — Reproduksi audit

Harness audit berada di luar repository (scratchpad sesi) dan tidak di-commit:

- `schema.sql` dibangkitkan dari `1.json`.
- `generate.sql` membuat data sintetis.
- `queries.sql`, `variants.sql`, dan `covering.sql` berisi query yang diukur.
- Semua dijalankan via `Audit.java` dengan `sqlite-jdbc` 3.21.0.1 dan 3.46.1.0.

Data sintetis hanya dipakai untuk pengukuran dan tidak pernah masuk aplikasi.

---

## Addendum B — Keputusan review (mengikat)

Ditambahkan setelah review pemilik proyek. Urutan sub-fase 4.0–4.6 tetap. Bila bertentangan
dengan bagian di atas, addendum ini yang berlaku.

### B1. Median merchant ditunda (menggantikan S2 dan bagian median di §5.3, §7, §16 4.6)

S2 mengumpulkan nominal per merchant di memori tanpa batas yang dapat dijamin, sehingga **S2 tidak
diimplementasikan di Fase 4**. Sub-fase 4.6 hanya mencakup per merchant: **total, count, mean
(Σ/n), share, ranking** (by total dan by frekuensi), cakupan data merchant, merchant baru (Q4),
perubahan total/frekuensi, dan insight I3. Semua berasal dari agregasi SQL Q3 dan digabung per kunci
normalisasi di Kotlin; memori O(jumlah varian merchant). `MerchantStats.median` dihapus dari model.
Median per merchant masuk daftar "tidak dikerjakan".

### B2. Snapshot konsisten untuk S1 (menggantikan mekanisme S1 di §6)

Syarat: COUNT dan stream nominal harus membaca snapshot yang sama.

Desain final, dengan dua lapis jaminan:

1. **Satu statement.** COUNT dibaca dari subquery skalar tak berkorelasi di statement yang sama:

   ```sql
   SELECT amount,
          (SELECT COUNT(*) FROM transactions
           WHERE +deletedAt IS NULL AND type = 'EXPENSE'
             AND transactionDate >= ?1 AND transactionDate < ?2) AS n
   FROM transactions
   WHERE +deletedAt IS NULL AND type = 'EXPENSE'
     AND transactionDate >= ?1 AND transactionDate < ?2
   ORDER BY amount
   ```

   Satu statement SQLite selalu membaca satu snapshot, terlepas dari mode transaksi Room. Hasil
   verifikasi di SQLite 3.21: plan `EXECUTE SCALAR SUBQUERY 1` (dievaluasi sekali, bukan per baris),
   dan `n` = jumlah baris stream (8.195 = 8.195). Nilai `n` dibaca dari baris pertama untuk
   menentukan rank; jumlah baris yang benar-benar di-stream dibandingkan dengan `n`, dan
   ketidaksamaan dilempar sebagai error, tidak diabaikan.
2. **Read transaction.** Statement dijalankan di dalam
   `useReaderConnection { it.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) { usePrepared(...) } }`.

Bukti API Room 2.8.5 (bytecode):

- FinTrack memakai `SupportSQLiteOpenHelper`. `RoomConnectionManager` lalu membuat
  `PassthroughConnectionPool(SupportSQLiteDriver)`.
- `PassthroughConnection.withTransaction(DEFERRED)` mengeksekusi `BEGIN DEFERRED TRANSACTION` …
  `END TRANSACTION` pada koneksi yang sama.
- Semantik SQLite diverifikasi dengan dua koneksi:
  - Journal **WAL**: writer commit di tengah transaksi baca, tetapi COUNT kedua tetap sama.
  - Journal **DELETE**: writer diblokir (`SQLITE_BUSY`) sampai transaksi baca selesai.
  - Keduanya **konsisten**.

Yang **belum** terbukti dan menjadi **kriteria penerimaan 4.5**: bahwa jalur framework Android
(Robolectric) menjalankan statement di dalam transaksi yang sama. Ini diuji dengan test konkuren:
penulisan dari coroutine lain di antara pembacaan tidak boleh mengubah hasil stream, dan
`n` = jumlah baris. Jika lapis 2 terbukti tidak berlaku, lapis 1 tetap menjamin konsistensi.

### B3. `todayFlow` tanpa bergantung pada timer (menggantikan §9 butir "hari ini")

`todayFlow` di `core/util` memancarkan `LocalDate` WIB yang **dihitung ulang dari `Clock`** pada
setiap pemicu berikut, lalu `distinctUntilChanged()`:

1. **Mulai dikumpulkan / aplikasi kembali ke foreground.** Layar mengumpulkan state dengan
   `collectAsStateWithLifecycle` (STARTED) dan ViewModel memakai `SharingStarted.WhileSubscribed`.
   Saat aplikasi kembali ke foreground, upstream di-subscribe ulang dan tanggal dihitung ulang.
   Tidak ada dependency baru (`ProcessLifecycleOwner` tidak diperlukan).
2. **Broadcast sistem** `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED`, `ACTION_DATE_CHANGED`
   melalui `callbackFlow` + `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` selama
   dikumpulkan. Broadcast ini dikirim sistem saat jam/zona/tanggal berubah.
3. **Penanda waktu menuju tengah malam WIB** (`delay` sampai 00:00 WIB berikutnya) hanya sebagai
   pemicu tambahan saat aplikasi terbuka melewati tengah malam. Kebenaran tidak bergantung
   padanya: setiap pemicu menghitung ulang dari `Clock`, sehingga `delay` yang tertunda (Doze)
   paling lambat dikoreksi oleh pemicu 1 atau 2.

Catatan: zona laporan selalu Asia/Jakarta. Perubahan zona perangkat tidak mengubah tanggal
laporan, tetapi tetap memicu hitung ulang (aman dan murah). Kriteria 4.0 ditambah dengan test
berikut, semuanya menggunakan clock yang dapat dimajukan:

- melewati tengah malam memicu emisi baru;
- broadcast waktu memicu emisi baru;
- subscribe ulang menghitung ulang;
- tidak ada emisi ganda untuk tanggal yang sama.

### B4. Bucket NULL category terpisah dari hierarki (menyempurnakan §5.2)

`CategoryAnalysis.byParent` hanya memproses `categoryId != null`. Pengeluaran tanpa kategori
dibawa sebagai field terpisah `uncategorized: Long` (beserta count), tidak menjadi node, tidak
menjadi induk, dan tidak pernah digabung ke induk mana pun. Bucket ini tampil sebagai baris
tersendiri "Tanpa kategori" di kedua tampilan.

Invarian yang diuji:
`Σ rolledUp(root) + uncategorized = Σ flat = E(P)`.

Kasus test:

- hanya NULL;
- campuran;
- anak dengan induk yang diarsipkan;
- categoryId yang tidak ditemukan di tabel kategori (dilaporkan sebagai bucket "kategori tidak
  dikenal" per id, bukan NULL).

### B5. Perubahan kategori memperbarui label analitik

Label, induk, dan status arsip **tidak pernah disalin ke hasil query agregasi**. SQL hanya
mengembalikan `categoryId`. Join ke label terjadi di Kotlin dengan `combine` bersama
`CategoryRepository.observeAllCategories()`, yaitu Room Flow pada tabel `categories`. Akibatnya:

- rename, arsip, atau perubahan induk otomatis memancarkan ulang label tanpa query agregasi ulang;
- perubahan induk mengubah hasil roll-up `byParent` karena roll-up dihitung ulang dari peta
  kategori terbaru.

Kriteria penerimaan 4.2 ditambah test ViewModel berikut terhadap data yang sedang ditampilkan:

- rename kategori → label baru tampil;
- arsip → label "Diarsipkan" tampil;
- pindah induk → total per induk berubah, total keseluruhan tetap.

### B6. Hari dalam minggu: aman sebelum dan sesudah Unix epoch (menggantikan Q5 dan §3.4)

Temuan saat menerapkan keputusan ini:

1. Rumus §3.4 `((date + 25 200 000) / 86 400 000 + 3) % 7` **salah untuk timestamp sebelum
   1970**. SQLite memotong pembagian integer ke arah nol, dan `%` mengikuti tanda pembilang.
   Simulasi aritmetika SQLite pada 200.000 timestamp 1900–1969 menghasilkan **199.997 salah**.
2. **Asia/Jakarta bukan UTC+7 sebelum 1964** (tzdata: +7:07:12 LMT, +7:20, +9:00, +7:30).
   Offset tetap absolut tidak sah untuk tanggal lama, padahal date picker Material mengizinkan
   tahun 1900.

Desain final: hari dihitung **relatif terhadap awal rentang**, tanpa offset absolut.

```sql
SELECT ((transactionDate - :start) / :dayMillis + :startDow) % 7 AS dow,
       COUNT(*) AS cnt, SUM(amount) AS total
FROM transactions
WHERE +deletedAt IS NULL AND type = 'EXPENSE'
  AND transactionDate >= :start AND transactionDate < :end
GROUP BY dow
```

- `:start` adalah tengah malam WIB hari pertama (via `java.time`).
- `:startDow` (Senin = 0) dihitung di Kotlin dari tanggal pertama.
- `transactionDate − :start ≥ 0` dijamin oleh klausa WHERE, sehingga pembagian tidak pernah
  negatif.
- Syarat satu-satunya sama dengan pengelompokan harian Fase 3: setiap hari dalam rentang harus
  tepat 24 jam (`ReportRepository` sudah memeriksanya dan gagal keras). Rentang yang melintasi
  perubahan offset historis, seperti tahun baru 1964, ditolak dengan pesan eksplisit, bukan
  dihitung salah.
- Rumus absolut di §3.4 tidak dipakai di kode mana pun.

Test wajib (ditambahkan ke kriteria 4.4):

- Rentang **sebelum epoch** (Juni 1969), **melintasi epoch** (25 Des 1969 – 5 Jan 1970), dan
  **sesudah epoch**. Hasil `dow` dari SQL dibandingkan dengan `java.time` untuk setiap
  transaksi, termasuk timestamp −1 ms, −25 200 001 ms, dan 0.
- Rentang 1950-an yang konsisten (+7:30, hari 24 jam) dihitung benar.
- Rentang yang melintasi transisi 31 Des 1963 → 1 Jan 1964 ditolak dengan error yang jelas.
- Pemeriksaan "hari 24 jam" yang sama juga melindungi Q1/Q2.

### Ringkasan perubahan rencana

| Sub-fase | Perubahan |
|---|---|
| 4.0 | `todayFlow` versi B3 + test pemicu |
| 4.2 | B4 (NULL terpisah), B5 (label reaktif) + test |
| 4.4 | Q5 versi B6 (relatif awal rentang) + test sebelum/sesudah epoch dan transisi 1964 |
| 4.5 | S1 versi B2 (satu statement + read transaction) + test konkuren |
| 4.6 | Tanpa median merchant (B1) |

