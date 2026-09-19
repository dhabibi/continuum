# Local account storage

Continuum's anonymous implementation uses `.anonymous` throughout its Room DAOs,
feed loader, subscription writers and multireddit membership tables. Local
accounts reuse that implementation inside separate database files. They do not
pretend to be authenticated Reddit usernames or mutate the anonymous constant.

`account/LocalProfiles.java` owns a private registry of UUIDs and display names.
Its current identity is fixed for the process lifetime. Selecting another account
persists the next launch's identity and uses the existing app restart helper to
reopen Home. In-flight requests and cached database instances continue to refer
to their original account until the process exits.

The Default account keeps the existing `reddit_data` database and original
preference/cache paths. There is no initial copy, rename, or destructive migration.
Other accounts use UUID-based database filenames and directories; display names
never become filesystem paths. Each database retains the upstream schema,
migrations and anonymous-row callback. Subscriptions, multireddits, follows,
history, drafts and other database-backed data therefore stay with their owner.

Current Reddit-account preferences, Home tabs, scroll anchors, FeedCache,
SavedPostCache and ResumeState are partitioned too. Proxy/API configuration,
appearance and ordinary browsing preferences remain shared. Keep new navigation
or identity storage within this boundary; changing only the database is not
enough to prevent another account's cached feed or screen from appearing.

Reminders retain their profile identity in alarm intents. Startup checks all
profiles for outstanding alarms, and the receiver removes a fired reminder from
its owner's database. Default alarms retain their pre-existing PendingIntent
identity for compatibility.

Settings backup/restore operates on the selected local account's database and
shared preferences. The local-account registry is not restored from those ZIPs;
restoring into a selected account leaves the other local accounts' databases
alone. The backup screen names that scope when multiple local accounts exist.

`LocalProfilesTest` exercises separate Room files, colliding multireddit paths,
existing Default data, saved screen paths and account preference separation.
The pending Android validation and APK build hold are recorded in current_state.md.
