# GymOps setup guide for gym owners

This guide is written for someone who has never used a terminal. Follow the steps in order. After most steps there is a **What you should see** note so you know it worked. If something goes wrong, look at [Troubleshooting](#troubleshooting) near the end.

What you will do:

1. Try GymOps on your own computer (about 30 minutes).
2. Set up your gym inside the app: branches, plans, staff, payments, e-mail and SMS.
3. Optionally connect a turnstile or door with a fingerprint, card or QR reader.
4. Publish GymOps on the internet with your own web address, so staff and members can use it anywhere.
5. Give your gate supplier the information they need to connect the gates to your published site.

> **Gates are optional.** GymOps works perfectly well without any gate hardware: the front desk checks members in from the app. If you never plan to use turnstiles, you can skip sections 4 and 6, and an admin can switch the gate features off in **Settings** (see [Optional features: the Settings page](#optional-features-the-settings-page)).

A few words you will meet:

| Word | What it means |
|---|---|
| **Terminal** | A window where you type commands instead of clicking. On Windows it is called **PowerShell**; on a Mac it is called **Terminal**. |
| **Command** | One line of text you type (or paste) into the terminal and then press **Enter**. |
| **Docker** | A free program that runs GymOps and everything it needs (database, web server) in sealed boxes called **containers**. You install it once. |
| **`.env` file** | GymOps' settings file: passwords, e-mail settings, payment keys. It stays on your computer or server and must never be shared. |
| **Domain** | Your web address, for example `gym.example.com`. |
| **Server** | A computer in a data centre that is always on. You rent one to publish GymOps. |

---

## 1. What you need

- A **Windows 10/11** or **Mac** computer with **8 GB of memory (RAM) or more** and about **15 GB of free disk space**.
  - Windows: press **Windows + Pause** (or open **Settings → System → About**) and look at "Installed RAM".
  - Mac: Apple menu → **About This Mac** → "Memory".
- An **internet connection**.
- About **30 minutes**. The first start downloads and builds a lot, so most of that time is waiting.

---

## 2. Install GymOps on one computer (try it out)

### Step 1: Install Docker Desktop

1. Open https://www.docker.com/products/docker-desktop/ and click **Download Docker Desktop** for your system (Windows, or Mac with Apple chip / Intel chip; on a Mac, Apple menu → About This Mac shows "Chip: Apple M…" or "Processor: Intel").
2. Run the installer and accept the defaults.
   - **Windows:** if it asks about **WSL 2** (a Windows feature Docker needs), keep it ticked. Restart the computer when asked.
   - **Mac:** drag the Docker icon into **Applications**, then open Docker from Applications.
3. Open **Docker Desktop**. Accept the terms. You can skip signing in ("Continue without signing in") and skip the survey.

**What you should see:** a whale icon in the taskbar (Windows, bottom right) or the menu bar (Mac, top right), and the Docker Desktop window says **Engine running** in the bottom-left corner. Leave Docker Desktop running whenever you use GymOps.

### Step 2: Download GymOps

**Easiest way (no extra software):**

1. Open https://github.com/abhinyaay/GYM-crm
2. Click the green **Code** button, then **Download ZIP**.
3. Find the ZIP in your **Downloads** folder.
   - **Windows:** right-click it → **Extract All…** → **Extract**.
   - **Mac:** double-click it.

**What you should see:** a folder called **GYM-crm-main** in Downloads, containing files such as `README.md` and `docker-compose.yml`.

**Alternative for people who have Git installed:** in a terminal, run `git clone https://github.com/abhinyaay/GYM-crm.git`. This makes a folder called `GYM-crm`. Updating later is easier with Git (see [Stop, start and update](#step-10-stop-start-and-update)).

### Step 3: Open a terminal

- **Windows:** click **Start**, type **PowerShell**, and click **Windows PowerShell**.
- **Mac:** press **Command + Space**, type **Terminal**, and press **Enter**.

**What you should see:** a window with a blinking cursor after some text such as `PS C:\Users\Asha>` (Windows) or `asha@MacBook ~ %` (Mac).

> **How to run a command:** copy the grey command text from this guide, paste it into the terminal (Windows: right-click; Mac: Command + V), and press **Enter**. Wait until the blinking cursor comes back before typing the next one.

### Step 4: Go to the GymOps folder

Type `cd`, a space, and then the folder location.

- **Windows:**

  ```powershell
  cd $HOME\Downloads\GYM-crm-main
  ```

- **Mac:**

  ```bash
  cd ~/Downloads/GYM-crm-main
  ```

Tip: you can also type `cd ` (with the space), drag the GymOps folder from your file window into the terminal, and press Enter.

**What you should see:** the text before the cursor now ends with `GYM-crm-main`. Typing `ls` and Enter lists files including `docker-compose.yml`.

### Step 5: Create the settings file

This creates the `.env` settings file with strong random passwords. You do this **once**.

- **Windows (PowerShell):**

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\generate-env.ps1
  ```

  (`-ExecutionPolicy Bypass` lets this one script run; Windows blocks downloaded scripts by default.)

- **Mac:**

  ```bash
  ./scripts/generate-env.sh
  ```

  If you see "permission denied", run `sh scripts/generate-env.sh` instead.

- **Windows with Git Bash or WSL (optional):** `sh scripts/generate-env.sh` also works there. Git Bash comes with Git for Windows and includes the `openssl` tool the script uses. If you downloaded the code with `git clone` on Windows and see errors about `\r`, use the PowerShell command above instead.

**What you should see:** `Generated .env (bootstrap admin + demo passwords are inside it)`. If it says `.env already exists - leaving it untouched`, you already did this step; that is fine.

> **Before the first start: demo data.** The settings file starts with **demo data switched on**: 3 example branches, about 360 made-up members, a year of payments and visits. That is great for looking around. If you want to start with an **empty** gym instead, open the `.env` file (Step 7 shows how) and change `DEMO_DATA_ENABLED=true` to `DEMO_DATA_ENABLED=false` **before** Step 6.

### Step 6: Start GymOps

```bash
docker compose up -d --build
```

The first time this takes **5 to 15 minutes**: it downloads and builds everything. Later starts take under a minute.

**What you should see:** many lines scroll past, ending with lines like `✔ Container gymops-nginx-1  Started`. To check that everything is healthy, run:

```bash
docker compose ps
```

Every line should say **Up** and, after a minute or two, **(healthy)**. Seven containers: postgres, redis, mailpit, core-service, analytics-service, web and nginx.

### Step 7: Open GymOps and sign in

1. Open your web browser and go to **http://localhost**
2. Find your admin e-mail and password in the `.env` file:
   - **Windows:** run `notepad .env`
   - **Mac:** run `open -e .env`
   - Look for the lines `BOOTSTRAP_ADMIN_EMAIL=` (default `superadmin@gymops.dev`) and `BOOTSTRAP_ADMIN_PASSWORD=` (a long random password starting with `Adm1n`).
3. Sign in with that e-mail and password.
4. **Change the password right away:** open the user menu in the top-right corner → **Profile & password**, enter the current password and a new one, and save.

**What you should see:** the GymOps dashboard. This guide is also available inside the app: **Setup guide** in the menu (or http://localhost/guide).

> **Keep the `.env` file private.** It contains every password of your installation. Never e-mail it, post it, or upload it to GitHub. Do keep a copy somewhere safe (for example a password manager), because you need it to move or restore GymOps.

### Step 8: Demo data on or off

- With demo data on, you can also sign in as the example users, for example `admin@gymops.dev`, `manager.blr@gymops.dev`, `frontdesk.blr@gymops.dev`, `trainer1.blr@gymops.dev` or `member1.blr@gymops.dev`. They all use the password in `DEMO_USER_PASSWORD=` in `.env`.
- **To switch demo data off for real use:** set `DEMO_DATA_ENABLED=false` in `.env`. Demo data that was already created stays until you erase the database. To start completely fresh (this **deletes everything** in GymOps on this computer):

  ```bash
  docker compose down -v
  docker compose up -d --build
  ```

  The `-v` means "also delete the stored data". Only use it when you really want to start over.

### Step 9: Where e-mails show up (while testing)

On your own computer GymOps does not send real e-mails. Every e-mail (receipts, reminders, password resets) lands in a test inbox called **Mailpit**: open **http://localhost:8025**.

To send real e-mails, see [E-mail and SMS settings](#e-mail-and-sms-settings).

### Step 10: Stop, start and update

Run these from the GymOps folder (Step 4).

| To… | Run |
|---|---|
| Stop GymOps (your data is kept) | `docker compose down` |
| Start it again | `docker compose up -d` |
| Apply changes you made in `.env` | `docker compose up -d` |
| See whether it is running | `docker compose ps` |

> **Never** run `docker compose down -v` or `make down` unless you want to erase all data. Both delete the database.

**Updating to a newer version of GymOps:**

- If you used **git clone**: run `git pull`, then `docker compose up -d --build`.
- If you used the **ZIP**: make a backup first (Step 11). Download the new ZIP and unzip it, copy your `.env` file from the old folder into the new one, go to the new folder in the terminal and run `docker compose up -d --build`. Your data is kept: it lives inside Docker, not in the folder.

### Step 11: Backups

Make a backup before every update and regularly (for example weekly on a test computer, daily on the live server). Run these two commands from the GymOps folder; they work the same on Windows and Mac:

```bash
docker compose exec postgres pg_dump -U gymops -d gymops -Fc -f /tmp/gymops.dump
docker compose cp postgres:/tmp/gymops.dump ./gymops-backup.dump
```

**What you should see:** a file `gymops-backup.dump` in the GymOps folder. Rename it with today's date and copy it somewhere safe (an external drive or cloud storage). Keep a copy of your `.env` file with it.

On a Mac or Linux server you can also do it in one line: `docker compose exec -T postgres pg_dump -U gymops -Fc gymops > gymops-backup.dump`

**Restoring a backup** (this replaces the current data with the backup):

```bash
docker compose stop core-service analytics-service
docker compose cp ./gymops-backup.dump postgres:/tmp/gymops.dump
docker compose exec postgres pg_restore -U gymops -d gymops --clean --if-exists /tmp/gymops.dump
docker compose start core-service analytics-service
```

If you changed `POSTGRES_USER` or `POSTGRES_DB` in `.env`, use those names instead of `gymops`.

---

## 3. First-day setup in the app

Sign in as the admin. The menu on the left has everything below.

### Branches

**Branches → New branch.** Fill in the name, a short code (for example `BLR-KOR`), address, **Timezone** (for India: `Asia/Kolkata`) and capacity. For GST invoices fill in **GSTIN** (your 15-character GST number) and **GST state**. Open the branch afterwards and use the **Operating hours** tab to set the hours for each day; gates and check-in refuse entry outside them.

### Plans

**Plans → New plan.** Choose the name, billing period and duration, **Price (₹, excl. GST)**, joining fee, freeze and cancellation rules. Under **Access rules** you can:

- allow access to all branches or only the home branch,
- **Restrict access hours (off-peak)** with "Access from" and "Access until",
- set **Max visits / month**,
- set **Max entries per day**: how many times a member may enter per day; leave it empty for unlimited.

New plans start as drafts: **activate** a plan to put it on sale.

### Staff users

**Users → New staff account** (admins only). Pick the role:

| Role | Can do |
|---|---|
| Gym admin | Everything, including settings and users |
| Manager | Runs branches: plans, billing, reports, messaging, devices |
| Receptionist | Front desk: members, check-in, payments |
| Trainer | Classes, sessions and workout plans of their members |

Each staff member signs in with their own e-mail and changes their password under **Profile & password**.

### Payment settings

Payment settings live in the `.env` file (Step 7 shows how to open it). After changing them, run `docker compose up -d`.

- **Simulated (default, `PAYMENT_GATEWAY=simulated`):** no real money moves. The online checkout shows a "test mode" page with test cards. Use it for trying things out. Cash, bank transfer and counter payments recorded by staff always work.
- **Razorpay (real online payments in India):**
  1. Create an account at https://razorpay.com and complete the business verification.
  2. In the Razorpay Dashboard go to **Account & Settings → API Keys** and generate a key. Start in **Test mode**; switch to **Live mode** keys only when everything works.
  3. In `.env` set:

     ```
     PAYMENT_GATEWAY=razorpay
     RAZORPAY_KEY_ID=rzp_test_...
     RAZORPAY_KEY_SECRET=...
     ```

  4. In the Razorpay Dashboard go to **Webhooks → Add new webhook**. Webhook URL: `https://your-domain/api/v1/webhooks/razorpay` (this only works once GymOps is published, see section 5). Choose a secret and put the same value in `RAZORPAY_WEBHOOK_SECRET=`. Tick the events `payment.captured`, `payment.failed`, `token.confirmed`, `token.paused`, `token.cancelled` and `token.rejected`.
  5. Run `docker compose up -d` and make a small test payment.

  Honest note: the Razorpay connection is built to Razorpay's public documentation and tested against a simulated Razorpay, but has not yet been run against a live Razorpay account. Test thoroughly with test keys first.

### E-mail and SMS settings

Also in `.env`, followed by `docker compose up -d`.

**E-mail** (`EMAIL_PROVIDER=smtp`). Pick one provider:

| Provider | Settings |
|---|---|
| **Gmail** (small gyms, up to about 500 e-mails a day) | Turn on 2-Step Verification for the Google account, then create an **app password** at https://myaccount.google.com/apppasswords. `SMTP_HOST=smtp.gmail.com`, `SMTP_PORT=587`, `SMTP_USERNAME=` your Gmail address, `SMTP_PASSWORD=` the 16-letter app password, `SMTP_STARTTLS_REQUIRED=true`, `MAIL_FROM_ADDRESS=` the same Gmail address |
| **Amazon SES** (large volumes, cheapest) | Verify your domain in SES and create **SMTP credentials**. `SMTP_HOST=email-smtp.<region>.amazonaws.com` (for Mumbai: `email-smtp.ap-south-1.amazonaws.com`), `SMTP_PORT=587`, `SMTP_USERNAME` / `SMTP_PASSWORD` = the SMTP credentials, `SMTP_STARTTLS_REQUIRED=true`, `MAIL_FROM_ADDRESS=` an address on your verified domain |
| **SendGrid** | Create an API key. `SMTP_HOST=smtp.sendgrid.net`, `SMTP_PORT=587`, `SMTP_USERNAME=apikey` (literally the word apikey), `SMTP_PASSWORD=` the API key, `SMTP_STARTTLS_REQUIRED=true`, `MAIL_FROM_ADDRESS=` a verified sender |

Also set `MAIL_FROM_NAME=` (your gym's name) and `PUBLIC_BASE_URL=` (your web address, used in links inside e-mails).

**SMS** (optional; only urgent messages such as failed payments and expiring memberships go by SMS):

- **MSG91** (recommended in India, `SMS_PROVIDER=msg91`). Indian SMS rules require **DLT registration**: register your business, sender ID (6 letters, for example `GYMOPS`) and message templates on a DLT portal (for example Jio, Airtel or Vodafone-Idea). Then in MSG91 create a Flow template whose text uses `##var1##` (title) and `##var2##` (message). Set `MSG91_AUTH_KEY`, `MSG91_SENDER_ID`, `MSG91_TEMPLATE_ID` and `MSG91_DLT_TEMPLATE_ID`.
- **Twilio** (`SMS_PROVIDER=twilio`): set `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` and either `TWILIO_FROM_NUMBER` or `TWILIO_MESSAGING_SERVICE_SID`. Sending to Indian numbers still needs DLT registration.
- `SMS_PROVIDER=log` (default) sends nothing.

**Test it:** open **Messaging** in the menu and click **Send test message**. Choose e-mail or SMS and a recipient. The page also shows which provider is active and whether it is configured. **What you should see:** the message arrives within a minute (on your own computer, e-mails arrive in Mailpit, http://localhost:8025).

### Optional features: the Settings page

Admins have a **Settings** page in the menu with two switches (both start **on**):

- **Gate access control.** Switch it **off** if you do not use turnstiles or doors. The Access control menu, the member's Entry pass and the member's Gate access tab disappear, and gate devices are refused. The front desk checks members in as usual.
- **Fingerprint & face.** Switch it **off** if you want gates **without fingerprints**: members then use an **RFID card** or the **QR pass** in the member app. Biometric consent and enrolment are hidden and fingerprint / face scans are refused (reason `BIOMETRICS_DISABLED`). Existing enrolments are kept in case you switch it back on.

---

## 4. Connecting the fingerprint / card / QR gate (optional)

Skip this section if you do not use gates.

### How it works

The fingerprint itself **never leaves the reader**. The reader compares the finger with the fingerprints stored inside it and only reports "this is template number 1234". Then the gate (or a small helper program next to it, called the **bridge**) asks GymOps "may template 1234 enter?" and GymOps answers **OPEN** or **DENIED** with a reason. GymOps applies exactly the same rules as the front desk: active membership, plan hours, opening hours, daily entry limit, capacity and so on.

```
  Member                Gym (local network)                              Internet
 --------    ------------------------------------------------    ------------------------
                                                                  
  finger   ->  [ Fingerprint / card / QR reader ]                   
  card         matches the finger INSIDE the reader              
  QR code               |                                         
                        | "template 1234" / card number          
                        v                                         
               [ Bridge: small PC or Raspberry Pi ]  --- HTTPS -->  [ GymOps website ]
               (or the reader itself, if it can       "may 1234      checks membership,
                speak the GymOps protocol)             enter?"       hours, limits ...
                        |                            <-- signed --   answers OPEN / DENIED
                        | open for 5 seconds              answer
                        v
               [ Turnstile / door relay ]  ->  gate opens
```

If the internet drops, the bridge keeps working from a signed list of allowed cards and fingerprints that it downloaded earlier, and it sends the missed entries to GymOps when the internet is back.

### Step 1: Register the gate in GymOps

1. Go to **Access control → Devices → Register device**.
2. Fill in a **name** (for example "Main entrance turnstile"), the **branch**, the **type** (turnstile, door or kiosk) and the **direction**:
   - **Entry**: only for coming in,
   - **Exit**: only for leaving,
   - **Both**: one reader for both directions.
3. Click **Register**.

**What you should see:** a window with the **Device ID** (starts with `gd_`) and the **device secret** (64 letters and digits). **The secret is shown only once.** Copy both into a password manager or give them directly to your gate installer. Then click **I have stored the secret**. If the secret is ever lost or leaked, use **Rotate** on the device row to make a new one (the old one stops working immediately).

### Step 2: Configure the bridge

Your installer (or you) sets up the bridge program next to the gate as described in [Running the GymOps gate bridge](#running-the-gymops-gate-bridge). It needs the web address of GymOps, the Device ID and the secret.

### Step 3: Test with the Gate simulator

Before real hardware is connected you can test everything in the browser: **Access control → Gate simulator** (http://localhost/access/gate). Choose the device, paste its secret, then tap a demo card, present an enrolled fingerprint or scan a QR code. **What you should see:** a big green **OPEN** or a red **DENIED** with the reason.

### Step 4: Enrol members

Open **Members**, pick a member and open the **Gate access** tab.

- **Fingerprint or face:**
  1. Click **Record consent** first. The member must read and accept the consent notice (Indian data-protection law requires explicit consent for biometrics).
  2. Under **Biometric**, choose **Fingerprint** (or Face) and the device the member is standing at, then **Register**.
  3. The device (through the bridge) receives an enrolment request. The member places the finger on the reader; the reader reports its template number back and the credential becomes **Active**.
- **RFID card:** click **Register card** and type the card number (the hexadecimal number printed on the card, or tap the card on a USB card reader while the cursor is in the field).
- **QR pass:** nothing to enrol. Members open **Entry pass** in the member app and hold the QR code to the scanner. The code changes every 30 seconds, so a screenshot cannot be shared.

The member can withdraw biometric consent at any time; their fingerprints are then deleted from all devices.

### How members leave: exit options

Each branch chooses how people leave, in **Access control → Entry & exit settings**:

- **Exit button (free exit)**, also called push-button exit: only **entry** is verified. People leave by pressing the exit button or walking through the exit side. GymOps closes the visit automatically after the assumed visit time, or when the member enters next time. Best for most gyms.
- **Scan to exit** (verified exit): members scan their fingerprint, card or QR again to leave. **Anti-passback** then stops pass sharing: a card that is already "inside" cannot be used to let a second person in.

### Daily entry limit

In each plan, **Max entries per day** sets how many times a member may enter per day; leave it empty for unlimited. A member who has used up the day's entries gets **DENIED** with the reason `DAILY_ENTRY_LIMIT`, at the gate and at the front desk alike.

### Fire safety

> **Exit buttons and the emergency release must always open the door, whatever the software does.** Ask your installer to wire the exit button and the fire-alarm release directly to the door or turnstile controller (and to use fail-safe locks where your fire code requires them), not only through GymOps or the bridge. GymOps also logs exit-button presses, but the door must open even if the computer, the internet or GymOps is down.

---

## 5. Publishing the website (going live)

On your own computer, GymOps is only reachable from that computer. To let staff and members use it from anywhere, run it on a **server** with your own **domain** and a padlock (**HTTPS**, meaning the connection is encrypted).

### Option A (recommended): one cloud server

This is the simplest path: one rented server running exactly the same setup you tried locally, plus **Caddy**, a small program that gets and renews the free HTTPS certificate automatically. Expect roughly US$20 to 25 a month for the server.

**1. Rent a server.** Any of these work; pick a data centre close to your gym (for India: Mumbai, or Bangalore at DigitalOcean):

- AWS Lightsail: https://lightsail.aws.amazon.com
- DigitalOcean: https://www.digitalocean.com
- Hetzner Cloud: https://www.hetzner.com/cloud

Choose **Ubuntu 24.04 LTS**, at least **4 GB memory**, **2 CPUs** and **80 GB disk**. Turn on the provider's **automatic snapshots** (a daily copy of the whole server; small extra cost). Write down the server's **public IP address** (four numbers like `203.0.113.25`).

**2. Open only the needed doors (firewall).** In the provider's **Networking / Firewall** settings allow only:

| Port | Why |
|---|---|
| 22 (SSH) | So you can log in to the server. If possible, only from your own IP address |
| 80 (HTTP) | Needed for getting the certificate; visitors are redirected to HTTPS |
| 443 (HTTPS) | The website itself |

Everything else stays closed.

**3. Point your domain at the server.** Buy a domain at any registrar (for example GoDaddy, Namecheap, Hostinger or Cloudflare). In the registrar's **DNS settings** add an **A record**: name `@` (or a sub-name such as `gym`), value = your server's IP address. It can take from a few minutes to a few hours to work. **What you should see:** opening a terminal and running `ping gym.example.com` shows your server's IP address.

**4. Log in to the server.** The easiest way is the provider's browser console (Lightsail: **Connect using SSH**; DigitalOcean: **Console**). You get a terminal on the server.

**5. Install Docker and download GymOps** (paste one line at a time):

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
exit
```

Log in again (so the last line takes effect), then:

```bash
git clone https://github.com/abhinyaay/GYM-crm.git
cd GYM-crm
./scripts/generate-env.sh
nano .env
```

**6. Edit the settings.** `nano` is a simple text editor; use the arrow keys to move. Change or add these lines:

```
COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml
DOMAIN=gym.example.com
ACME_EMAIL=you@example.com
BOOTSTRAP_ADMIN_EMAIL=you@example.com
DEMO_DATA_ENABLED=false
BUSINESS_TIMEZONE=Asia/Kolkata
```

plus your e-mail (SMTP), SMS and payment settings from section 3. The `COMPOSE_FILE` line makes every `docker compose` command use the production setup automatically. `DOMAIN` is your web address without `https://`; `ACME_EMAIL` receives certificate warnings. Save with **Ctrl + O**, **Enter**, then leave with **Ctrl + X**.

**7. Start GymOps:**

```bash
docker compose up -d --build
```

**What you should see:** after 5 to 15 minutes, `docker compose ps` lists all containers as healthy, including **caddy**. Open `https://gym.example.com` (your domain): the site loads with a padlock. Sign in with `BOOTSTRAP_ADMIN_EMAIL` and the `BOOTSTRAP_ADMIN_PASSWORD` from `.env` (run `grep BOOTSTRAP .env` to see them), then change the password under **Profile & password**.

The production setup (files [`docker-compose.prod.yml`](../docker-compose.prod.yml) and [`infra/caddy/Caddyfile`](../infra/caddy/Caddyfile)) adds Caddy on ports 80 and 443, hides the internal web server from the internet, switches demo data off, and sets the web address used in e-mails and by the browser security rules (`PUBLIC_BASE_URL`, `CORS_ALLOWED_ORIGINS`) to `https://` + your domain. It also starts everything again automatically after a server restart.

**8. Daily backups on the server.** Run `crontab -e` (choose nano if asked) and add this line at the bottom (one line), then save:

```
0 2 * * * mkdir -p $HOME/backups && cd $HOME/GYM-crm && docker compose exec -T postgres pg_dump -U gymops -Fc gymops > $HOME/backups/gymops-$(date +\%F).dump
```

This makes a backup every night at 02:00 in the `backups` folder. Copy backups off the server now and then (or rely on the provider's snapshots as well), and delete old ones so the disk does not fill up.

**9. Updating the server later:**

```bash
cd ~/GYM-crm
git pull
docker compose up -d --build
```

### Option B: AWS with a technical team

For larger chains that want automatic scaling, managed databases with failover and zero-downtime updates, GymOps also ships an AWS setup (ECS Fargate, RDS PostgreSQL, ElastiCache, load balancer) built with Terraform. It needs an engineer to run. See [AWS deployment guide](AWS_DEPLOYMENT.md).

### Go-live checklist

- [ ] Admin password changed under **Profile & password**; every staff member has their own account.
- [ ] `DEMO_DATA_ENABLED=false` and no demo branches or members left (start fresh if they were created).
- [ ] The `.env` file is backed up in a safe, private place.
- [ ] Nightly backups run and you have **tested a restore** once.
- [ ] Payments: Razorpay **live** keys in `.env`, webhook URL `https://your-domain/api/v1/webhooks/razorpay` with the same secret as `RAZORPAY_WEBHOOK_SECRET`, one small real payment tested and refunded.
- [ ] E-mail: real SMTP provider set, **Messaging → Send test message** arrives in a real inbox, `MAIL_FROM_ADDRESS` is your domain or verified address.
- [ ] SMS (if used): DLT-registered sender and templates, test SMS arrives.
- [ ] Firewall: only ports 80 and 443 open to everyone (22 only for you).
- [ ] Time zone: `BUSINESS_TIMEZONE` and each branch's timezone are correct; the server clock is synchronised (`timedatectl` shows "System clock synchronized: yes").
- [ ] Branch opening hours, plans and prices checked.
- [ ] Gates (if used): each gate registered, self-test passes, exit buttons and fire release open the door with the power or internet off.

---

## 6. How the gate vendor connects devices to the published site

This section is for you **and** for the company that installs your gates. You can send them the link to this section.

### The short version

- The gate (or the bridge next to it) makes **outbound HTTPS calls** to `https://your-domain/api/v1/access/gate/...`. Nothing calls **into** the gym, so **no port forwarding and no static IP** are needed at the gym. The gym's firewall only has to allow **outgoing port 443**.
- **Each gate has its own Device ID and secret** (Access control → Devices). Every request is signed with the secret; GymOps signs every answer back, so a fake server cannot open the gate.
- **The clock must be right.** Requests more than **5 minutes** off GymOps' clock are rejected. Turn on automatic time (NTP) on the bridge computer or the device.
- **Internet outage:** the bridge keeps deciding from the **signed offline allowlist** (downloaded every hour, valid for 24 hours), stores the events, and uploads them when the connection is back. QR passes, visit caps, daily limits and live capacity cannot be checked offline.
- A **403 `FEATURE_DISABLED`** answer means the gym switched **Gate access control** off in **Settings**; nothing is wrong with the device.

### Typical hardware

| Hardware | How it connects |
|---|---|
| **Standalone fingerprint / face terminals** (ZKTeco, eSSL, Suprema, Matrix and others) | Match the finger or face **on the device** and report a user / template number. They talk through the vendor's SDK or "push" protocol (for example the ADMS / iclock push mode on ZKTeco and eSSL). The vendor's integrator turns those events into calls to the bridge's local HTTP input, or implements the GymOps spec below directly. |
| **USB RFID card readers and USB QR scanners** | Most act like a **keyboard**: they "type" the card number or QR text and press Enter. The bridge reads them directly (keyboard input). |
| **Turnstile / door controllers and relay boards** | The bridge switches them: by running a program (for example a USB relay tool), by calling a network relay board's URL, or by leaving it to the terminal's own relay. |

**Honest note:** GymOps does not include vendor-specific SDK code. The generic bridge below covers keyboard-type card and QR readers directly, and any device or vendor middleware that can call a local web address. For proprietary fingerprint terminals, the vendor's integrator connects the device's SDK or push events to the bridge's local HTTP input, or implements the [integration spec](#integration-spec-for-the-vendors-engineer) on their side.

### Running the GymOps gate bridge

The bridge is one file, [`scripts/gate_bridge.py`](../scripts/gate_bridge.py), that runs on a small PC or a Raspberry Pi next to the gate. It needs **Python 3.10 or newer** and nothing else.

```
 [USB card / QR reader] --types--> +-------------------+
                                   |                   | --- HTTPS (signed) ---> GymOps
 [Fingerprint terminal / vendor    |   gate_bridge.py  | <-- signed answers ----
  middleware] --POST localhost --> |                   |
                                   +-------------------+
                                       |          |
                       open relay <----+          +----> offline allowlist + event buffer (disk)
```

1. Install Python: Raspberry Pi OS and Ubuntu already have it (`python3 --version`); on Windows install it from https://www.python.org/downloads/ and tick "Add python.exe to PATH". On Windows also run `pip install tzdata` once (time-zone data).
2. Copy `scripts/gate_bridge.py` and `scripts/gate_bridge.example.json` to the bridge computer. Rename the JSON file to `gate_bridge.json` and fill in `server_url` (`https://your-domain`), `device_id` and `direction`. See the notes inside the file for inputs and relay settings.
3. Put the device secret in an environment variable, never in the file:
   - Linux / Raspberry Pi: `export GATE_DEVICE_SECRET='paste-the-secret'`
   - Windows PowerShell: `$env:GATE_DEVICE_SECRET = 'paste-the-secret'`
4. Check the connection:

   ```bash
   python3 gate_bridge.py --config gate_bridge.json --self-test
   ```

   **What you should see:** `Heartbeat: HTTP 200, response signature verified`, the clock difference, the allowlist size and `RESULT: OK - the bridge can talk to GymOps`.
5. Try it without switching the relay: `python3 gate_bridge.py --config gate_bridge.json --dry-run`, then tap a card. **What you should see:** a line like `GRANTED OK ENTRY card ...002A Priya S. [server] open 5s`.
6. Run it for real: `python3 gate_bridge.py --config gate_bridge.json`

**Inputs** (setting `inputs` in the JSON file):

- `keyboard`: a USB card or QR reader typing into the bridge's console. Card numbers become card scans, text starting with `GQ1.` becomes a QR scan, `EXIT` is an exit-button press, `FP:<number>` a fingerprint template. Set `card_format` to `decimal` if your reader types decimal numbers.
- `http`: a local web endpoint (default `http://127.0.0.1:8765`) for fingerprint terminals and vendor software:

  ```
  POST /scan   {"credentialType":"FINGERPRINT","credentialValue":"<template number>","direction":"ENTRY"}
  POST /event  {"event":"EXIT_BUTTON"}
  POST /alarm  {"type":"DOOR_FORCED","details":"..."}
  POST /commands/<id>/ack  {"success":true,"templateRef":"<template number>"}
  GET  /status   GET /commands
  ```

  The answer is the decision as JSON (`decision`, `reasonCode`, `openGate`, `openSeconds`, `memberDisplayName`, ...), so the caller can drive its own relay if `drive_relay` is false. If another computer on the LAN must call it, set `bind` to `0.0.0.0` and a token in the environment variable `GATE_BRIDGE_HTTP_TOKEN`; callers then send the header `X-Bridge-Token`.
- `exit-button`: a USB push button that sends Enter; every press is an exit-button event.

**Relay** (setting `relay`): `print` (just log, default), `command` (run a program such as a USB relay tool, with `{seconds}` replaced by the open time; give it as a list of words, it is never run through a shell), or `http` (call a network relay board's URL). `deny_signal` works the same way for a red LED or beep.

**Fingerprint enrolment:** when staff click Register for a fingerprint, the bridge prints (and, if `commands.forward_url` is set, forwards to the vendor software) an `ENROLL_TEMPLATE` command. After the reader has captured the finger, the vendor software calls `POST /commands/<id>/ack` with the template number (or an operator types `!ack <id> <template number>` in the bridge console).

**Start automatically on a Raspberry Pi / Linux** with a systemd service. Save as `/etc/systemd/system/gymops-gate.service` (adjust the paths and user), then run `sudo systemctl enable --now gymops-gate`:

```
[Unit]
Description=GymOps gate bridge
After=network-online.target time-sync.target
Wants=network-online.target

[Service]
User=pi
WorkingDirectory=/home/pi/gymops-gate
Environment=GATE_DEVICE_SECRET=paste-the-secret
ExecStart=/usr/bin/python3 /home/pi/gymops-gate/gate_bridge.py --config /home/pi/gymops-gate/gate_bridge.json
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Protect the file (`sudo chmod 600 /etc/systemd/system/gymops-gate.service`) because it contains the secret. A service has no keyboard: for a USB keyboard-type reader, also add `StandardInput=tty`, `TTYPath=/dev/tty1` and `TTYReset=yes` under `[Service]`, and disable the login prompt on that screen with `sudo systemctl disable getty@tty1`. On Windows, use **Task Scheduler** → "Create task" → run at log-on, with the same command.

### Integration spec (for the vendor's engineer)

Base URL: `https://<gym domain>/api/v1/access/gate`. JSON in and out (`Content-Type: application/json`). Times are ISO-8601 UTC (`2026-10-13T10:00:00Z`); clock times are `HH:mm:ss` in the branch time zone.

**Request headers (every call):**

| Header | Value |
|---|---|
| `X-Device-Id` | The Device ID, e.g. `gd_e4853c9598b7db4b7aee` |
| `X-Timestamp` | Current time in **epoch seconds** (must be within ±300 s of the server) |
| `X-Nonce` | Random, **single use**, 16 to 64 characters from `A-Z a-z 0-9 _ -` |
| `X-Signature` | `hex(HMAC-SHA256(secret, METHOD + "\|" + PATH + "\|" + X-Timestamp + "\|" + X-Nonce + "\|" + hex(SHA-256(body))))` |

- `secret` is the 64-character device secret used as text (its UTF-8 bytes are the HMAC key).
- `PATH` is the request path as the server receives it, including any query string, for example `/api/v1/access/gate/events`. An empty body (GET) hashes to `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- Sign the **exact bytes** you send.
- **Every successful response is signed:** `X-Signature = hex(HMAC-SHA256(secret, "response|" + X-Nonce + "|" + hex(SHA-256(response body))))`. Open the gate only when this verifies and `openGate` is `true`. Error responses (4xx) are not signed and never mean "open".

**Test vector** (secret = 64 times the letter `a`):

```
method     POST
path       /api/v1/access/gate/events
timestamp  1760000000
nonce      0123456789abcdef0123456789abcdef
body       {"deviceEventId":"evt-1","credentialType":"RFID_CARD","credentialValue":"D300002A"}
sha256     17c845a374d7cea6f57df545c095011a834eb6aa497af341c64d83534dde846c
canonical  POST|/api/v1/access/gate/events|1760000000|0123456789abcdef0123456789abcdef|17c845a374d7cea6f57df545c095011a834eb6aa497af341c64d83534dde846c
signature  598402f520e2331282c3fb6ca006070864c83677a76881df93463e8c185d107b

response body {"decision":"GRANTED"} with the same nonce
response signature  0c982d1c4fc33ab78c0d75341a1e34e1be7a12cca43056cdca7f484946a3a5ef
```

Reference code: [`scripts/gate_simulator.py`](../scripts/gate_simulator.py) (about 40 lines of signing code) and [`scripts/gate_bridge.py`](../scripts/gate_bridge.py).

**Endpoints:**

| Call | Purpose |
|---|---|
| `POST /heartbeat` | Every 60 s. Keeps the device "online"; managers are alerted after 5 minutes of silence |
| `POST /events` | A scan: returns OPEN / DENIED |
| `POST /events/batch` | Upload scans decided while offline |
| `POST /exit-button` | An exit-button press (no credential) |
| `GET /commands` | Pending enrol / delete commands |
| `POST /commands/{id}/ack` | Report that a command was carried out |
| `POST /alarms` | Door forced, held open, tailgating, tamper |
| `GET /allowlist` | Signed list for offline decisions |

**Heartbeat.** Request (all fields optional):

```json
{"firmwareVersion": "zk-6.60", "uptimeSeconds": 86400, "offlineQueueDepth": 0, "doorState": "CLOSED"}
```

Response:

```json
{"serverTime": "2026-10-13T10:00:00Z", "deviceStatus": "ACTIVE", "pendingCommands": 1,
 "allowlistRefreshSeconds": 3600, "exitMode": "BUTTON"}
```

`exitMode` is `BUTTON` (free exit) or `VERIFIED` (scan to exit). When `pendingCommands` is above 0, poll `/commands`.

**Scan.** Request:

```json
{"deviceEventId": "zk01-000123", "credentialType": "FINGERPRINT", "credentialValue": "1234", "direction": "ENTRY"}
```

- `deviceEventId`: unique per scan, up to 80 characters from `A-Z a-z 0-9 . _ : -`. Re-sending the same id returns the original decision (`"replayed": true`) and never counts twice, so retries are safe.
- `credentialType`: `RFID_CARD` (value = card UID in hexadecimal, separators allowed), `FINGERPRINT` or `FACE` (value = the device's template / user reference, up to 80 characters from `A-Z a-z 0-9 . _ : -`), `QR` (value = the scanned text, starts with `GQ1.`).
- `direction`: `ENTRY` or `EXIT`; leave it out to use the device's registered direction.

Response (`GateDecision`):

```json
{"eventId": 912, "deviceEventId": "zk01-000123", "decision": "GRANTED", "reasonCode": "OK", "message": "Welcome",
 "openGate": true, "openSeconds": 5, "memberDisplayName": "Priya S.", "direction": "ENTRY", "offline": false,
 "antiPassbackViolation": false, "replayed": false, "serverTime": "2026-10-13T10:00:01Z"}
```

**Offline batch.** Up to 500 events, in the order they happened, each with `occurredAt` and the decision the device took:

```json
{"events": [
  {"deviceEventId": "zk01-000124", "credentialType": "RFID_CARD", "credentialValue": "D300002A", "direction": "ENTRY",
   "occurredAt": "2026-10-13T09:12:00Z", "offlineDecision": "GRANTED"},
  {"deviceEventId": "zk01-000125", "credentialType": "RFID_CARD", "credentialValue": "0A0B0C0D", "direction": "ENTRY",
   "occurredAt": "2026-10-13T09:13:00Z", "offlineDecision": "DENIED", "offlineReason": "UNKNOWN_CREDENTIAL"}
]}
```

Response: `{"processed": 2, "results": [GateDecision, ...]}`. The server records what happened at `occurredAt` and flags events its own rules would have refused.

**Exit button.** Request `{"deviceEventId": "zk01-exit-0042", "occurredAt": "2026-10-13T09:20:00Z"}` (`occurredAt` only for presses buffered while offline). Response: a `GateDecision`; it always opens. Logged as an anonymous exit.

**Commands.** `GET /commands` returns:

```json
{"commands": [{"id": 72, "type": "ENROLL_TEMPLATE", "credentialType": "FINGERPRINT", "templateRef": null,
               "memberRef": "M001003", "createdAt": "2026-10-13T09:00:00Z", "deliveryCount": 1}]}
```

- `ENROLL_TEMPLATE`: capture the member's finger / face on this device, then acknowledge with the device's reference: `POST /commands/72/ack` `{"success": true, "templateRef": "1234", "message": "enrolled"}`. On failure send `{"success": false, "message": "finger not readable"}`.
- `DELETE_TEMPLATE`: erase `templateRef` from the device, then acknowledge with `{"success": true}`.
- Commands are delivered again on every poll until they are acknowledged.

**Alarms.** `POST /alarms` `{"deviceEventId": "zk01-alarm-7", "type": "DOOR_FORCED", "occurredAt": "2026-10-13T09:30:00Z", "details": "rear door"}`. Types: `DOOR_FORCED`, `DOOR_HELD_OPEN`, `TAILGATING`, `TAMPER`. Branch managers are notified.

**Offline allowlist.** `GET /allowlist` returns:

```json
{"deviceId": "gd_...", "branchId": 2, "branchCode": "MUM-AND", "timezone": "Asia/Kolkata",
 "generatedAt": "2026-10-13T09:00:00Z", "validUntil": "2026-10-14T09:00:00Z", "hashScheme": "...",
 "openingHours": [{"dayOfWeek": 1, "openTime": "05:30:00", "closeTime": "22:30:00", "closed": false}],
 "entries": [{"credentialType": "RFID_CARD", "credentialHash": "2e6f99b1...", "memberRef": "M001003",
              "displayName": "Sneha R.", "validFrom": "2026-10-13T09:00:00Z", "validUntil": "2026-10-14T00:00:00Z",
              "dailyAccessStart": null, "dailyAccessEnd": null}],
 "notes": "...", "exitMode": "BUTTON"}
```

- Verify the response signature and keep it with the cached copy.
- Match a card with `SHA-256("RFID_CARD:" + UID)`, where the UID is upper-case hexadecimal without spaces, `:` or `-` (example: `D300002A` gives `2e6f99b13ad099ce406181cb5d4eccc081a3049fc3a66bb785b224350ce98c66`). Match a fingerprint / face with `SHA-256("TEMPLATE:" + type + ":" + templateRef)`.
- Admit only when: now is within the entry's `validFrom` / `validUntil` and before the list's `validUntil`; the branch is open (`dayOfWeek` 1 = Monday … 7 = Sunday, in `timezone`; no `openingHours` = always open; a missing day = closed); and, when set, the local time is within `dailyAccessStart` / `dailyAccessEnd`.
- Refresh every `allowlistRefreshSeconds`. Offline you cannot check QR passes, monthly visit caps, daily entry limits, live capacity or anti-passback across devices.

**Errors:**

| Status | Meaning | What the device should do |
|---|---|---|
| 400 | Malformed request (the message says which field) | Deny; fix the integration |
| 401 | Bad signature, unknown device, clock more than 5 minutes off (the message then includes the server time), or a reused nonce | Deny; check the secret and NTP |
| 403 `ACCESS_DENIED` | The device is **disabled** in GymOps | Deny |
| 403 `FEATURE_DISABLED` | The gym switched **Gate access control** off in Settings | Deny new entries, keep exits working, check back every few minutes |
| 429, 5xx, timeout | Server busy or unreachable | Decide offline from the allowlist and buffer the event |

**Reason codes** (`reasonCode`):

| Code | Meaning |
|---|---|
| `OK` | Allowed |
| `UNKNOWN_CREDENTIAL` | Card / fingerprint not registered |
| `CREDENTIAL_PENDING` | Enrolment not finished |
| `CREDENTIAL_REVOKED` / `CREDENTIAL_SUSPENDED` | Card or fingerprint withdrawn / paused by staff |
| `CONSENT_WITHDRAWN` | Member withdrew biometric consent |
| `BIOMETRICS_DISABLED` | Fingerprint / face switched off in Settings; use card or QR |
| `QR_INVALID` / `QR_EXPIRED` / `QR_REUSED` | QR code wrong, too old, or already used |
| `MEMBER_SUSPENDED` / `MEMBER_INACTIVE` | Member account suspended or inactive |
| `NO_ACTIVE_MEMBERSHIP` | No valid membership today |
| `FROZEN` | Membership is frozen |
| `OUTSIDE_PLAN_HOURS` | Plan only allows certain hours |
| `BRANCH_NOT_ALLOWED` | Plan is not valid at this branch |
| `VISIT_LIMIT` | Monthly visits used up |
| `DAILY_ENTRY_LIMIT` | Entries for today used up |
| `AT_CAPACITY` | Branch is full |
| `BRANCH_CLOSED` | Outside opening hours |
| `ANTI_PASSBACK` | Already inside (or not inside, for an exit) |
| `DIRECTION_NOT_ALLOWED` | Entry on an exit-only device or the other way round |
| `OFFLINE_DENIED` | Refused by the device while offline |

---

## Troubleshooting

| Problem | What to do |
|---|---|
| `docker` is "not recognized" / "command not found" | Docker Desktop is not installed or not running. Open Docker Desktop, wait for **Engine running**, then open a **new** terminal window. |
| Docker Desktop on Windows complains about WSL or virtualisation | Restart the computer. If it still fails, follow the link Docker shows to update WSL (`wsl --update` in PowerShell). Virtualisation must be enabled in the computer's BIOS; ask whoever set up the PC. |
| `generate-env.ps1 cannot be loaded because running scripts is disabled` | Use the full command from Step 5, including `-ExecutionPolicy Bypass`. |
| `set POSTGRES_PASSWORD in .env` (or a similar "required variable") | The `.env` file is missing. Run Step 5 in the GymOps folder. |
| `port is already allocated` or "address already in use" for port 80 | Another program uses port 80. Add the line `HTTP_PORT=8088` to `.env`, run `docker compose up -d`, and open http://localhost:8088 instead. |
| http://localhost does not load right after starting | Wait two minutes; the first start is slow. Run `docker compose ps` until everything says **healthy**. |
| A container keeps restarting or says **unhealthy** | Run `docker compose logs --tail=50 core-service` (or the name of the failing container) and look for the first error. Not enough memory is a common cause: in Docker Desktop **Settings → Resources**, give Docker at least 4 GB. |
| Wrong password on sign-in | Use exactly `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` from `.env`. Changing them in `.env` later does **not** change an existing admin; use **Profile & password** in the app. |
| E-mails do not arrive | On your own computer they go to Mailpit (http://localhost:8025). With real SMTP: check **Messaging** for the provider status and failed messages; for Gmail you need an **app password**, not your normal password. |
| The live site shows no padlock / certificate error | The domain's A record must point to the server and ports 80 and 443 must be open. Check with `docker compose logs caddy`. It can take a few minutes after DNS starts working. |
| `DOMAIN` "required variable is missing" on the server | Add `DOMAIN=` and `ACME_EMAIL=` to `.env` (section 5, step 6). |
| Gate bridge self-test: HTTP 401 | Wrong Device ID or secret (was it rotated?), or the bridge computer's clock is more than 5 minutes off. Turn on automatic time. |
| Gate bridge: HTTP 403 `FEATURE_DISABLED` | Gates are switched off in **Settings → Gate access control**. |
| Gate bridge: "server unreachable" | Check the internet connection, the `server_url` (must start with `https://`) and that the gym's firewall allows outgoing port 443. Meanwhile the bridge works offline. |
| Fingerprint denied with `BIOMETRICS_DISABLED` | **Fingerprint & face** is switched off in **Settings**. Switch it on, or use cards / QR. |
| Card denied with `UNKNOWN_CREDENTIAL` although it was registered | Some readers type the card number in decimal or in reverse byte order. Compare what the bridge logs with the number registered in GymOps and set `card_format` / `reverse_card_bytes` in the bridge settings. |
| I deleted something by mistake | Restore the latest backup (section 2, Step 11). |
