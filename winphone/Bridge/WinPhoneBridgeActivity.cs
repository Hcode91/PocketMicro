// PocketMicro <-> WPR bridge (Windows Phone edition of PocketMicro).
//
// PocketMicro's Java launcher never shows WPR's own screens. Everything it needs from WPR goes
// through this one activity, started with an explicit component and one of the actions below:
//
//   INSTALL      pick a .xap, run WPR's installer (XapInstallFlow), refresh games.json
//   LAUNCH       start an installed game in WPR's game process (GameLauncher), report how it ended
//   UNINSTALL    remove a game (ApplicationInstaller.UninstallAsync), refresh games.json
//   EXPORT_LOGS  save WPR's logs as one .zip wherever the player picks
//   ACHIEVEMENTS open WPR's achievements page (every game, or one game with productId)
//
// games.json and the tile images live in PocketMicro's files/winphone folder, which the Java side
// (WinPhone.java) reads to show Windows Phone games in the library.
//
// This file is compiled into WPR's Android head by winphone/prepare-wpr.py; it is PocketMicro's
// code (GPL-3.0), the rest of WPR is MIT.

using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

using Android.App;
using Android.Content;
using Android.Content.PM;
using Android.Graphics;
using Android.OS;
using Android.Runtime;

using Microsoft.EntityFrameworkCore;
using Microsoft.Xna.Framework.GamerServices;

using Newtonsoft.Json;

using WPR.Common;

using WprApplication = WPR.Models.Application;

namespace WPR.Platform.Android.Native
{
    [Activity(
        Label = "Windows Phone",
        Theme = "@style/WprTheme",
        Exported = false,
        ConfigurationChanges = ConfigChanges.Orientation | ConfigChanges.ScreenSize)]
    [Register("com.hcode91.pocketmicro.winphone.WinPhoneBridge")]
    public class WinPhoneBridgeActivity : Activity
    {
        const string Prefix = "com.hcode91.pocketmicro.winphone.";
        const string ActionInstall = Prefix + "INSTALL", ActionLaunch = Prefix + "LAUNCH",
            ActionUninstall = Prefix + "UNINSTALL", ActionExportLogs = Prefix + "EXPORT_LOGS",
            ActionAchievements = Prefix + "ACHIEVEMENTS";
        const string ExtraProduct = "productId", ExtraLogging = "logging";
        const string ExtraOutcome = "outcome", ExtraDetail = "detail";
        const int RequestSaveLogs = 4401;
        const string Tag = "PocketMicroWinPhone";

        /// <summary>Work is over; finish as soon as no WPR dialog is covering this window.</summary>
        bool _Done;
        string? _ProductId;
        bool _Logging;

        protected override void OnCreate(Bundle? savedInstanceState)
        {
            base.OnCreate(savedInstanceState);
            // Recreated after the process was killed: the request already ran once.
            if (savedInstanceState != null) { Finish(); return; }

            try
            {
                WprStartup.EnsureInitialized(this);
            }
            catch (Exception ex)
            {
                Fail("WPR could not start", ex.ToString());
                return;
            }

            _ProductId = Intent?.GetStringExtra(ExtraProduct);
            _Logging = Intent?.GetBooleanExtra(ExtraLogging, false) ?? false;
            string action = Intent?.Action ?? "";
            Log.Info(LogCategory.AppList, $"[pocketouya] bridge {action} {_ProductId}");

            switch (action)
            {
                case ActionInstall:
                    XapInstallFlow.StartPicker(this);
                    break;
                case ActionLaunch:
                    Launch();
                    break;
                case ActionUninstall:
                    _ = UninstallAsync();
                    break;
                case ActionExportLogs:
                    StartLogExport();
                    break;
                case ActionAchievements:
                    OpenAchievements();
                    break;
                default:
                    WriteIndex();
                    Complete(Result.Ok, "Game list refreshed", null);
                    break;
            }
        }

        // ------------------------------------------------------------------ launch

        void Launch()
        {
            WprApplication? app = Find(_ProductId);
            if (app == null)
            {
                WriteIndex();
                Complete(Result.Canceled, "Not installed", "This Windows Phone game is no longer installed in PocketMicro.");
                return;
            }
            Log.Info(LogCategory.AppLaunch, $"[pocketouya] launching {app.Name} ({app.ProductId}, {app.ApplicationType}, patched v{app.PatchedVersion}){(_Logging ? " with logging" : "")}");
            GameLauncher.Launch(this, app, _Logging);
        }

        void OnGameResult(Result resultCode, Intent? data)
        {
            // GameLauncher.HandleGameResult also does WPR Hub reporting and rating prompts, which
            // PocketMicro leaves out; these are the parts of it that matter here.
            try { Directory.SetCurrentDirectory(WprStartup.PatchAssembliesDirectory(this)); } catch (Exception) { }
            // New unlocks, and a catalogue the launch may have generated, for the library tiles.
            WriteIndex();
            if (_ProductId != null) WPR.Shell.DiagnosticRun.Take(_ProductId);

            if (resultCode == Result.Ok)
            {
                Complete(Result.Ok, "Game exited normally", null);
                return;
            }

            string? error = data?.GetStringExtra(GameActivity.ErrorDataName);
            if (string.IsNullOrWhiteSpace(error))
                error = "The game process exited unexpectedly (native crash or force-close). Export the Windows Phone logs for details.";
            Log.Error(LogCategory.AppList, $"[pocketouya] game run error: {error}");
            try { File.WriteAllText(System.IO.Path.Combine(GetExternalFilesDir(null)!.AbsolutePath, "last_game_error.txt"), error); }
            catch (Exception) { }
            Complete(Result.Canceled, "Game stopped with an error", Trim(error!, 6000));
        }

        // ------------------------------------------------------------ achievements

        /// <summary>
        /// WPR's own achievements page: every game with a catalogue, or one game's list when a
        /// product id is given. Unlocks are recorded by the game process into achievements.db,
        /// which the page reads fresh, so it is current as soon as a game has exited.
        /// </summary>
        void OpenAchievements()
        {
            Intent page = new Intent(this, typeof(AchievementsActivity));
            WprApplication? app = Find(_ProductId);
            if (app != null)
            {
                page.PutExtra(AchievementsActivity.ExtraProductId, app.ProductId);
                page.PutExtra(AchievementsActivity.ExtraGameName, app.Name);
            }
            StartActivity(page);
            SetResult(Result.Ok);
            Finish();
        }

        /// <summary>Earned/total counts and gamerscore per product id, for the library tiles.</summary>
        static Dictionary<string, WPR.Shell.AchievementTotals> AchievementTotalsByProduct()
        {
            try
            {
                return AchievementContext.Current!.Achievements!
                    .AsNoTracking()
                    .ToList()
                    .Where(a => !string.IsNullOrEmpty(a.OwnProductId))
                    .GroupBy(a => a.OwnProductId!, StringComparer.OrdinalIgnoreCase)
                    .ToDictionary(g => g.Key, g => WPR.Shell.AchievementRollup.Totalise(g), StringComparer.OrdinalIgnoreCase);
            }
            catch (Exception ex)
            {
                Log.Warn(LogCategory.AppList, $"[pocketouya] achievement totals unreadable: {ex.Message}");
                return new Dictionary<string, WPR.Shell.AchievementTotals>(StringComparer.OrdinalIgnoreCase);
            }
        }

        // ----------------------------------------------------------------- install

        async Task InstallResultAsync(Result resultCode, Intent? data)
        {
            bool added = false;
            try
            {
                added = await XapInstallFlow.OnPickResultAsync(this, resultCode, data);
            }
            catch (Exception ex)
            {
                Log.Error(LogCategory.AppInstall, $"[pocketouya] install failed: {ex}");
                WpDialogs.Error(this, "installation failed", ex.Message);
            }
            WriteIndex();
            // A failed install has already shown WPR's error dialog; finishing waits for it.
            Complete(added ? Result.Ok : Result.Canceled, added ? "Game installed" : "Install did not complete", null);
        }

        // --------------------------------------------------------------- uninstall

        async Task UninstallAsync()
        {
            WprApplication? app = Find(_ProductId);
            if (app == null) { WriteIndex(); Complete(Result.Ok, "Already removed", null); return; }
            try
            {
                await ApplicationInstaller.UninstallAsync(app);
                try
                {
                    string installsRoot = Configuration.Current!.DataPath(WprApplication.DataStoreFolder);
                    await Task.Run(() => WPR.WindowsCompability.PerGameIsolatedStorage.ClearGameData(app.ProductId, installsRoot));
                }
                catch (Exception ex)
                {
                    Log.Warn(LogCategory.AppList, $"[pocketouya] removed {app.Name} but not its saves: {ex.Message}");
                }
                WriteIndex();
                Complete(Result.Ok, "Game removed", null);
            }
            catch (Exception ex)
            {
                Log.Error(LogCategory.AppList, $"[pocketouya] uninstall failed for {app.Name}:\n{ex}");
                WpDialogs.Error(this, "remove failed", ex.Message);
                Complete(Result.Canceled, "Remove failed", ex.Message);
            }
        }

        // -------------------------------------------------------------------- logs

        void StartLogExport()
        {
            Intent pick = new Intent(Intent.ActionCreateDocument);
            pick.AddCategory(Intent.CategoryOpenable);
            pick.SetType("application/zip");
            pick.PutExtra(Intent.ExtraTitle, $"PocketMicro-WindowsPhone-logs-{DateTime.Now:yyyyMMdd-HHmm}.zip");
            StartActivityForResult(pick, RequestSaveLogs);
        }

        void SaveLogs(global::Android.Net.Uri target)
        {
            try
            {
                using Stream? output = ContentResolver!.OpenOutputStream(target);
                if (output == null) throw new IOException("Could not open the chosen file.");
                using ZipArchive zip = new ZipArchive(output, ZipArchiveMode.Create);
                string root = GetExternalFilesDir(null)!.AbsolutePath;

                AddText(zip, "summary.txt", Summary());
                AddFile(zip, System.IO.Path.Combine(root, "last_game_error.txt"), "last_game_error.txt");
                string logs = Configuration.Current!.DataPath(SessionLog.FolderName);
                if (Directory.Exists(logs))
                    // Subfolders too: a logging run of a WP8 title writes its GPU trace dumps
                    // (render-target PNGs, shader bytecode) under Logs/wp8-debug/.
                    foreach (string f in Directory.GetFiles(logs, "*", SearchOption.AllDirectories))
                        AddFile(zip, f, "Logs/" + System.IO.Path.GetRelativePath(logs, f).Replace('\\', '/'));

                // Per-game log: written in "Play with logging" runs.
                string installs = Configuration.Current!.DataPath(WprApplication.DataStoreFolder);
                if (Directory.Exists(installs))
                    foreach (string dir in Directory.GetDirectories(installs))
                    {
                        string id = System.IO.Path.GetFileName(dir);
                        if (_ProductId != null && id != _ProductId) continue;
                        AddFile(zip, System.IO.Path.Combine(dir, "wpr_game_debug.log"), $"Games/{id}/wpr_game_debug.log");
                    }
                global::Android.Widget.Toast.MakeText(this, "Windows Phone logs saved", global::Android.Widget.ToastLength.Short)!.Show();
            }
            catch (Exception ex)
            {
                global::Android.Util.Log.Error(Tag, $"Log export failed: {ex}");
                global::Android.Widget.Toast.MakeText(this, "Log export failed: " + ex.Message, global::Android.Widget.ToastLength.Long)!.Show();
            }
        }

        string Summary()
        {
            var s = new StringBuilder();
            s.AppendLine($"PocketMicro Windows Phone logs, {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
            s.AppendLine($"Device: {Build.Manufacturer} {Build.Model}, Android {Build.VERSION.Release} (API {(int)Build.VERSION.SdkInt}), ABIs {string.Join(",", Build.SupportedAbis ?? new List<string>())}");
            s.AppendLine($"Patcher version: {ApplicationPatcher.Version}");
            s.AppendLine();
            s.AppendLine("Installed games:");
            foreach (WprApplication a in Installed())
                s.AppendLine($"  {a.Name} | {a.ProductId} | {a.ApplicationType} | version {a.Version} | patched v{a.PatchedVersion} | installed {a.InstalledTime:yyyy-MM-dd}");
            return s.ToString();
        }

        static void AddFile(ZipArchive zip, string path, string name)
        {
            try
            {
                if (!File.Exists(path)) return;
                ZipArchiveEntry entry = zip.CreateEntry(name, CompressionLevel.Optimal);
                using Stream to = entry.Open();
                // Shared read: the session log can still be open for writing.
                using FileStream from = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                from.CopyTo(to);
            }
            catch (Exception ex) { global::Android.Util.Log.Warn(Tag, $"Skipped {path}: {ex.Message}"); }
        }

        static void AddText(ZipArchive zip, string name, string text)
        {
            ZipArchiveEntry entry = zip.CreateEntry(name);
            using StreamWriter w = new StreamWriter(entry.Open(), new UTF8Encoding(false));
            w.Write(text);
        }

        // ------------------------------------------------------------ game list

        static List<WprApplication> Installed()
        {
            try { return WPR.Models.ApplicationContext.Current.Applications?.ToList() ?? new List<WprApplication>(); }
            catch (Exception ex) { Log.Error(LogCategory.AppList, $"[pocketouya] catalogue unreadable: {ex}"); return new List<WprApplication>(); }
        }

        static WprApplication? Find(string? productId) =>
            string.IsNullOrWhiteSpace(productId) ? null
                : Installed().FirstOrDefault(a => string.Equals(a.ProductId, productId, StringComparison.OrdinalIgnoreCase));

        /// <summary>Writes files/winphone/games.json (and a tile per game) for PocketMicro's library.</summary>
        void WriteIndex()
        {
            try
            {
                string folder = System.IO.Path.Combine(FilesDir!.AbsolutePath, "winphone");
                string tiles = System.IO.Path.Combine(folder, "tiles");
                Directory.CreateDirectory(tiles);
                var list = new List<Dictionary<string, string>>();
                Dictionary<string, WPR.Shell.AchievementTotals> totals = AchievementTotalsByProduct();
                foreach (WprApplication a in Installed())
                {
                    if (string.IsNullOrWhiteSpace(a.ProductId)) continue;
                    string tileName = "tiles/" + SafeName(a.ProductId) + ".png";
                    try
                    {
                        using Bitmap? art = GameTileArt.Decode(a);
                        if (art != null)
                        {
                            using FileStream f = File.Create(System.IO.Path.Combine(folder, tileName));
                            art.Compress(Bitmap.CompressFormat.Png!, 100, f);
                        }
                        else tileName = "";
                    }
                    catch (Exception ex) { tileName = ""; Log.Warn(LogCategory.AppList, $"[pocketouya] no tile for {a.Name}: {ex.Message}"); }
                    var entry = new Dictionary<string, string>
                    {
                        ["productId"] = a.ProductId,
                        ["name"] = string.IsNullOrWhiteSpace(a.Name) ? a.ProductId : a.Name,
                        ["type"] = a.ApplicationType.ToString(),
                        ["tile"] = tileName,
                    };
                    if (totals.TryGetValue(a.ProductId, out WPR.Shell.AchievementTotals t) && t.Total > 0)
                    {
                        entry["achEarned"] = t.Earned.ToString();
                        entry["achTotal"] = t.Total.ToString();
                        entry["gsEarned"] = t.EarnedScore.ToString();
                        entry["gsTotal"] = t.TotalScore.ToString();
                    }
                    list.Add(entry);
                }
                string tmp = System.IO.Path.Combine(folder, "games.json.tmp");
                File.WriteAllText(tmp, JsonConvert.SerializeObject(list));
                File.Move(tmp, System.IO.Path.Combine(folder, "games.json"), true);
            }
            catch (Exception ex)
            {
                Log.Error(LogCategory.AppList, $"[pocketouya] could not write games.json: {ex}");
            }
        }

        static string SafeName(string s) => new string(s.Select(c => char.IsLetterOrDigit(c) || c == '-' ? c : '_').ToArray());

        // --------------------------------------------------------------- results

        protected override void OnActivityResult(int requestCode, [GeneratedEnum] Result resultCode, Intent? data)
        {
            base.OnActivityResult(requestCode, resultCode, data);
            if (requestCode == XapInstallFlow.RequestPickXap)
            {
                if (resultCode != Result.Ok) { Complete(Result.Canceled, "Install cancelled", null); return; }
                _ = InstallResultAsync(resultCode, data);
            }
            else if (requestCode == GameLauncher.RequestGame) OnGameResult(resultCode, data);
            else if (requestCode == RequestSaveLogs)
            {
                if (resultCode == Result.Ok && data?.Data != null) SaveLogs(data.Data);
                Complete(Result.Ok, "Logs exported", null);
            }
        }

        void Fail(string outcome, string detail)
        {
            global::Android.Util.Log.Error(Tag, $"{outcome}: {detail}");
            Complete(Result.Canceled, outcome, Trim(detail, 6000));
        }

        void Complete(Result result, string outcome, string? detail)
        {
            Intent data = new Intent();
            data.PutExtra(ExtraOutcome, outcome);
            if (detail != null) data.PutExtra(ExtraDetail, detail);
            SetResult(result, data);
            _Done = true;
            // A WPR error dialog may be up; finishing now would take it away unread.
            if (HasWindowFocus) Finish();
        }

        public override void OnWindowFocusChanged(bool hasFocus)
        {
            base.OnWindowFocusChanged(hasFocus);
            if (hasFocus && _Done && !IsFinishing) Finish();
        }

        static string Trim(string s, int max) => s.Length <= max ? s : s.Substring(0, max) + "\n…(truncated)";
    }
}
