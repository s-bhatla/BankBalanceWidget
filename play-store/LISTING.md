# Bank Balance · Google Play listing draft

**App name:** Bank Balance

**Short description:** Turn bank SMS into private balance and spending widgets for Android.

## Full description

See your bank balance and spending at a glance, right on your Android home screen.

Bank Balance uses transaction SMS from supported Indian banks to update widgets for the accounts you choose. Add a balance widget or see what you’ve spent today, this week, and this month. Choose a color theme and use the optional eye button to hide amounts.

- Scan recent bank alerts to find accounts and confirm a starting balance.
- Let new transaction alerts update your widgets, or tap refresh to check recent messages.
- Tap a widget to correct the balance manually.

SMS processing stays on your device. Bank Balance has no sign-in, ads, or cloud sync. It shows the balance reported in a matching SMS when available and estimates it from transaction alerts otherwise. Coverage depends on your bank’s SMS format. It does not connect to your bank or move money.

## SMS permissions declaration draft

**Use case:** SMS-based money management

- `READ_SMS`: The user initiates an inbox scan to find supported bank accounts or refresh an account. Each scan inspects up to 100 recent inbox messages.
- `RECEIVE_SMS`: The app checks incoming SMS in the background for matching transaction alerts so balances and spending widgets can update automatically.

Processing happens on-device. Raw SMS bodies are not retained. The app has no internet permission. The SMS permissions enable the app’s core balance and spending features.

## Reviewer demo

Use a test inbox with synthetic bank alerts and an Android launcher that supports widgets. Show the disclosure and opt-in, account selection, the balance and spending widgets, a matching incoming SMS updating a widget, and refresh recovering a missed alert. Use synthetic balances and account endings in the demo and store screenshots.
