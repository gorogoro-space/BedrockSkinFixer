package space.gorogoro.bedrockskinfixer;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 統合版(Geyser/Floodgate)のプレイヤーのスキンを、GeyserMC の Global API から取得して適用する。
 */
public class BedrockSkinFixer extends JavaPlugin implements Listener {

  private static final String SKIN_API_URL = "https://api.geysermc.org/v2/skin/";
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

  private HttpClient httpClient = null;
  // 通信相手にプラグイン名とバージョンを名乗る(例: BedrockSkinFixer/1.0 (+https://github.com/gorogoro-space/BedrockSkinFixer))
  private String userAgent;
  private int joinDelayTicks;
  private int retryCount;
  private int retryIntervalSeconds;

  // 表示するメッセージ(config.yml の messages.<language>)。起動時に一度だけ読み込む
  private static final String DEFAULT_LANGUAGE = "en";
  private static final String[] MESSAGE_KEYS = {
          "op-only", "player-not-online", "console-needs-player", "not-bedrock",
          "fetching", "applied", "already-applied", "fetch-failed", "not-registered"
  };
  private final Map<String, String> messages = new HashMap<>();

  // 実行中の取得処理の番号(プレイヤーごと)。新しい取得を始めたときと退出したときに古いものを無効にする。メインスレッドだけで使う
  private final Map<UUID, Long> activeRequests = new HashMap<>();
  private long requestSequence = 0;

  /**
   * JavaPlugin method onEnable.
   */
  @Override
  public void onEnable() {
    try {
      getLogger().info("The Plugin Has Been Enabled!");

      // If there is no setting file, it is created
      if(!getDataFolder().exists()){
        getDataFolder().mkdir();
      }

      File configFile = new File(getDataFolder(), "config.yml");
      if(!configFile.exists()){
        saveDefaultConfig();
      }
      // 既存の config.yml に足りない項目があれば、jar 内の既定値を追記する
      if (!getConfig().getKeys(true).containsAll(getConfig().getDefaults().getKeys(true))) {
        getConfig().options().copyDefaults(true);
        saveConfig();
      }
      joinDelayTicks = Math.max(0, getConfig().getInt("join-delay-ticks"));
      retryCount = Math.max(0, getConfig().getInt("retry-count"));
      retryIntervalSeconds = Math.max(1, getConfig().getInt("retry-interval-seconds"));
      loadMessages();

      userAgent = getPluginMeta().getName() + "/" + getPluginMeta().getVersion()
              + " (+https://github.com/gorogoro-space/BedrockSkinFixer)";

      httpClient = HttpClient.newBuilder()
              .connectTimeout(HTTP_TIMEOUT)
              .build();

      getServer().getPluginManager().registerEvents(this, this);
    } catch (Exception e) {
      logStackTrace(e);
    }
  }

  /**
   * language で選んだ言語のメッセージを読み込む。無い言語なら英語にする。足りない項目は jar 内の英語で補う。
   */
  private void loadMessages() {
    String language = getConfig().getString("language");
    if (language == null || !getConfig().isConfigurationSection("messages." + language)) {
      getLogger().warning("Unknown language '" + language + "' in config.yml. Using '" + DEFAULT_LANGUAGE + "'.");
      language = DEFAULT_LANGUAGE;
    }
    messages.clear();
    for (String key : MESSAGE_KEYS) {
      String text = getConfig().getString("messages." + language + "." + key);
      if (text == null && getConfig().getDefaults() != null) {
        text = getConfig().getDefaults().getString("messages." + DEFAULT_LANGUAGE + "." + key);
      }
      messages.put(key, text != null ? text : key);
    }
  }

  /**
   * メッセージの {player} と {reason} を置き換えて返す。
   */
  private String message(String key, String player, String reason) {
    String text = messages.getOrDefault(key, key);
    if (player != null) {
      text = text.replace("{player}", player);
    }
    if (reason != null) {
      text = text.replace("{reason}", reason);
    }
    return text;
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    // Java 版のプレイヤーはここで抜ける
    if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) {
      return;
    }
    long requestId = startRequest(player.getUniqueId());
    // Geyser/Floodgate の参加処理が終わるのを待ってから取得する
    getServer().getScheduler().runTaskLater(this, () -> {
      if (!isActive(player, requestId)) {
        return;
      }
      FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
      // Java 版のアカウントとリンク済みの人は、Java 版のスキンを使うので自動では適用しない
      if (floodgatePlayer == null || floodgatePlayer.isLinked()) {
        activeRequests.remove(player.getUniqueId());
        return;
      }
      fetchSkin(player, floodgatePlayer.getXuid(), null, retryCount, requestId);
    }, joinDelayTicks);
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    activeRequests.remove(event.getPlayer().getUniqueId());
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    // OP のみ(コンソールも可)
    if (!sender.isOp()) {
      sender.sendMessage(Component.text(message("op-only", null, null), NamedTextColor.RED));
      return true;
    }
    Player target;
    if (args.length >= 1) {
      target = getServer().getPlayerExact(args[0]);
      if (target == null) {
        sender.sendMessage(Component.text(message("player-not-online", args[0], null), NamedTextColor.RED));
        return true;
      }
    } else if (sender instanceof Player) {
      target = (Player) sender;
    } else {
      sender.sendMessage(Component.text(message("console-needs-player", null, null), NamedTextColor.RED));
      return true;
    }
    // 手動ではリンク済みの人も対象にする
    FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(target.getUniqueId());
    if (floodgatePlayer == null) {
      sender.sendMessage(Component.text(message("not-bedrock", target.getName(), null), NamedTextColor.RED));
      return true;
    }
    sender.sendMessage(Component.text(message("fetching", target.getName(), null), NamedTextColor.GRAY));
    fetchSkin(target, floodgatePlayer.getXuid(), sender, 0, startRequest(target.getUniqueId()));
    return true;
  }

  /**
   * 新しい取得処理を始める。同じプレイヤーの古い取得処理(再試行の待ちを含む)は無効になる。
   */
  private long startRequest(UUID uuid) {
    long requestId = ++requestSequence;
    activeRequests.put(uuid, requestId);
    return requestId;
  }

  private boolean isActive(Player player, long requestId) {
    Long active = activeRequests.get(player.getUniqueId());
    return active != null && active == requestId && player.isOnline();
  }

  /**
   * Global API からスキンを取得する(メインスレッドから呼ぶ)。通信は別スレッドで行い、結果はメインスレッドで処理する。
   */
  private void fetchSkin(Player player, String xuid, CommandSender requester, int retriesLeft, long requestId) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(SKIN_API_URL + xuid))
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .GET()
            .build();
    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete((response, error) -> {
              // HttpClient のスレッドで呼ばれるので、ここでは Bukkit の API を使わずメインスレッドに戻す
              if (!isEnabled()) {
                return;
              }
              getServer().getScheduler().runTask(this, () ->
                      handleResponse(player, xuid, requester, retriesLeft, requestId, response, error));
            });
  }

  private void handleResponse(Player player, String xuid, CommandSender requester, int retriesLeft, long requestId,
                              HttpResponse<String> response, Throwable error) {
    if (!isActive(player, requestId)) {
      return;
    }
    String reason;
    if (error != null) {
      reason = error.getClass().getSimpleName() + ": " + error.getMessage();
    } else if (response.statusCode() != 200) {
      reason = "HTTP " + response.statusCode();
    } else {
      String[] textures = parseTextures(response.body());
      if (textures != null) {
        activeRequests.remove(player.getUniqueId());
        boolean changed = applyTextures(player, textures[0], textures[1]);
        if (requester != null && isOnlineOrConsole(requester)) {
          requester.sendMessage(Component.text(
                  message(changed ? "applied" : "already-applied", player.getName(), null), NamedTextColor.GREEN));
        }
        return;
      }
      // まだスキンがアップロードされていないと、空の応答 {} が返る
      reason = message("not-registered", null, null);
    }

    if (retriesLeft > 0) {
      getServer().getScheduler().runTaskLater(this, () -> {
        if (isActive(player, requestId)) {
          fetchSkin(player, xuid, requester, retriesLeft - 1, requestId);
        }
      }, retryIntervalSeconds * 20L);
      return;
    }
    activeRequests.remove(player.getUniqueId());
    String failed = message("fetch-failed", player.getName(), reason);
    getLogger().warning(failed);
    if (requester != null && isOnlineOrConsole(requester)) {
      requester.sendMessage(Component.text(failed, NamedTextColor.RED));
    }
  }

  /**
   * 応答の JSON から value と signature を取り出す。無ければ null。
   */
  private String[] parseTextures(String body) {
    try {
      JsonElement root = JsonParser.parseString(body);
      if (!root.isJsonObject()) {
        return null;
      }
      JsonObject json = root.getAsJsonObject();
      String value = getString(json, "value");
      String signature = getString(json, "signature");
      if (value == null || value.isEmpty() || signature == null || signature.isEmpty()) {
        return null;
      }
      return new String[] {value, signature};
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String getString(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
  }

  /**
   * スキンを適用する。既に同じスキンなら何もしない(周りへの再表示を避ける)。適用したら true。
   */
  private boolean applyTextures(Player player, String value, String signature) {
    PlayerProfile profile = player.getPlayerProfile();
    for (ProfileProperty property : profile.getProperties()) {
      if ("textures".equals(property.getName()) && value.equals(property.getValue())) {
        return false;
      }
    }
    profile.setProperty(new ProfileProperty("textures", value, signature));
    // Paper はプロフィールを変えると、そのプレイヤーを周りに表示し直す
    player.setPlayerProfile(profile);
    return true;
  }

  private boolean isOnlineOrConsole(CommandSender sender) {
    return !(sender instanceof Player) || ((Player) sender).isOnline();
  }

  public void logStackTrace(Exception e){
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      pw.flush();
      getLogger().warning(sw.toString());
  }

  /**
   * JavaPlugin method onDisable.
   */
  @Override
  public void onDisable() {
    try {
      activeRequests.clear();
      // 通信中のものがあれば打ち切る(待たない)
      if (httpClient != null) {
        httpClient.shutdownNow();
        httpClient = null;
      }
      getLogger().info("The Plugin Has Been Disabled!");
    } catch (Exception e) {
      logStackTrace(e);
    }
  }
}
