package io.papermc.paperweight.testplugin

import com.github.shynixn.mccoroutine.bukkit.launch
import com.github.shynixn.mccoroutine.bukkit.ticks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.FireworkMeta
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.checkerframework.checker.nullness.qual.NonNull
import org.checkerframework.framework.qual.DefaultQualifier
import org.incendo.cloud.SenderMapper
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.joml.AxisAngle4f
import org.joml.Vector3f


@DefaultQualifier(NonNull::class)
class TestPlugin : JavaPlugin(), Listener {
    override fun onEnable() {
      server.pluginManager.registerEvents(this, this)
      val manager: LegacyPaperCommandManager<CommandSender> = LegacyPaperCommandManager( /* Owning plugin */
        this,  /* (1) */
        ExecutionCoordinator.simpleCoordinator(),  /* (2) */
        SenderMapper.identity()
      )


      //
      // Configure based on capabilities
      //
      if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
        // Register Brigadier mappings for rich completions
        manager.registerBrigadier()
      } else if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
        // Use Paper async completions API (see Javadoc for why we don't use this with Brigadier)
        manager.registerAsynchronousCompletions()
      }
      manager.command(
        manager.commandBuilder("bs")
          .literal("종료")
          .handler {
            server.sendMessage(Component.text("[블럭 숨바꼭질] 게임이 종료되었습니다."))
            GameManager.isRunning = false

          }
      )
      manager.command(
        manager.commandBuilder("bs")
          .literal("스폰")
          .handler {
            server.sendMessage(Component.text("[블럭 숨바꼭질] 스폰위치를 설정하였습니다.."))
            GameManager.spawn = (it.sender() as Player).location
          }
      )
      manager.command(
        manager.commandBuilder("bs")
          .literal("시작")
          .handler {
            server.sendMessage(Component.text("[블럭 숨바꼭질] 게임이 시작됩니다."))
            GameManager.start(this,(it.sender() as Player))
          }
      )

    }
  @EventHandler
  fun onSneak(e : PlayerToggleSneakEvent){
    val p = e.player
    if (e.isSneaking && GameManager.isRunning && !p.isPredator()){

      GameManager.blockMap[p] = p.location.clone().add(0.0,-1.0,0.0).block.type
    }
  }
  @EventHandler
  fun onDeath(e : PlayerDeathEvent){
    if (e.player.isPredator()){
      GameManager.stop(this)
      server.showTitle(Title.title(Component.text("술래가 패배하였습니다."), Component.text("")))
    }else if(GameManager.players.contains(e.player)){
      e.player.gameMode = GameMode.SPECTATOR
      GameManager.players.remove(e.player)
      if (GameManager.players.isEmpty()){
        GameManager.stop(this)
        server.showTitle(Title.title(Component.text("술래가 승리하였습니다."), Component.text("")))

      }
    }
  }
  @EventHandler
  fun onUse( e : PlayerInteractEvent){
    val p = e.player
    if (p.isPredator()){
      if (e.item?.type == Material.BLAZE_ROD && GameManager.item >= 1){
        p.inventory.remove(ItemStack(Material.BLAZE_ROD,1))
        GameManager.item--
        GameManager.players.forEach {
          val firework: Firework = it.world.spawn(it.location, Firework::class.java)

          // 폭죽의 메타 데이터를 가져옴
          val fireworkMeta: FireworkMeta = firework.fireworkMeta

          // 폭죽의 효과를 설정 (예: 폭발형, 색상, 페이드 색상 등)
          fireworkMeta.addEffect(
            FireworkEffect.builder()
              .withColor(Color.RED)  // 폭죽의 색상
              .withFade(Color.YELLOW)  // 폭죽의 페이드 색상
              .with(FireworkEffect.Type.BALL_LARGE)  // 폭죽의 종류 (큰 구형 폭발)
              .trail(true)  // 폭죽의 꼬리 여부
              .flicker(true)  // 반짝이는 효과 추가
              .build()
          )

          // 폭죽의 비행 시간을 설정 (1-3)
          fireworkMeta.power = 1

          // 설정한 메타 데이터를 폭죽에 적용
          firework.fireworkMeta = fireworkMeta
          launch {
            delay((20 * 10) .ticks)
            p.inventory.addItem(ItemStack(Material.BLAZE_ROD,1))
          }
        }
      }
    }
  }
  override fun onDisable() {
    GameManager.stop(this)
  }
}

object GameManager{
  var isRunning = false
  var spawn : Location? = null
  lateinit var predator : Player
  var item = 0
  val maxTicks = 60 * 20 * 5 // 최대 틱 (60초)
  var bossBar: BossBar =BossBar.bossBar(
    Component.text(""),
    1f,
    BossBar.Color.BLUE,
    BossBar.Overlay.PROGRESS
  ) // 하나의 BossBar를 관리하기 위한 변수

  // 진행률 계산

  val players = mutableListOf<Player>()
  val blockMap = mutableMapOf<Player,Material>()
  var tick = 0
  fun start(plugin: Plugin,player: Player){
    plugin.launch {
      if (spawn == null){
        player.sendMessage("[블럭 숨바꼭질] spawn위치가 설정되지 않았습니다. ")
        return@launch
      }
      delay(20.ticks)
      Bukkit.getOnlinePlayers().forEach {
        it.sendTitle("3","")
        it.playSound(it.location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,1f)
      }
      delay(20.ticks)
      Bukkit.getOnlinePlayers().forEach {
        it.sendTitle("2","")
        it.playSound(it.location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,1f)
      }
      delay(20.ticks)
      Bukkit.getOnlinePlayers().forEach {
        it.sendTitle("1","")
        it.playSound(it.location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,1f)
      }
      predator = Bukkit.getOnlinePlayers().random()
      item = 3
      Bukkit.getOnlinePlayers().forEach {
        if (it != predator && it.gameMode != GameMode.SPECTATOR){
          players.add(it)
          blockMap[it] = Material.GRASS_BLOCK
        }
        it.teleportAsync(spawn?:return@launch)
      }
      predator.let {
        it.inventory.addItem(ItemStack(Material.DIAMOND_SWORD))
        it.inventory.addItem(ItemStack(Material.BLAZE_ROD,1))
      }
      isRunning = true
      plugin.server.showTitle(Title.title(Component.text(predator.name).decorate(TextDecoration.BOLD), Component.text("님이 술래가 되셨습니다.")))
      plugin.server.showBossBar(bossBar)

      while (true){
        delay(1.ticks)
        tick++
        val progress = 1 -((tick.toFloat() / maxTicks).coerceIn(0.0f, 1.0f))
        val time = convertTicksToMinutesAndSeconds(tick)
        bossBar.progress(progress)
        bossBar.name(Component.text("${time.first} : ${time.second}"))
        plugin.server.worlds.forEach {
          it.entities.forEach { entity->
            if (entity is FallingBlock){
              entity.remove()
            }
          }
        }
        if (tick == maxTicks) predator.health = 0.0
        blockMap.filter { !it.key.isPredator() } .forEach { (player, material) ->
          player.isInvisible = true
          val fallingBlock = player.world.spawnFallingBlock(player.location.toCenterLocation().clone().add(0.0,-0.5,0.0), material.createBlockData())
          fallingBlock.dropItem = false

          // 플레이어가 자신에게는 이 블록이 보이지 않도록 설정
          @Suppress("UnstableApiUsage")
          player.hideEntity(plugin, fallingBlock)

          // 블록 중력 제거 및 움직이지 않도록 설정
          fallingBlock.setGravity(false)
          fallingBlock.velocity = Vector(0, 0, 0)

        }
      }
    }
  }
  fun stop(plugin: Plugin){
    Bukkit.getOnlinePlayers().forEach {
      if (it.gameMode != GameMode.SPECTATOR){
        it.isInvisible = false
        it.teleportAsync(spawn?:return)
        it.gameMode = GameMode.ADVENTURE
      }
    }
    plugin.server.hideBossBar(bossBar)
  }
}

fun Player.isPredator(): Boolean {
  return this == GameManager.predator
}
fun convertTicksToMinutesAndSeconds(ticks: Int): Pair<Int, Int> {
  // 틱을 분과 초로 변환
  val minutes = 4 - (ticks / 1200)  // 1분 = 1200틱
  val seconds = 60 - (ticks % 1200) / 20  // 남은 틱을 초로 변환

  // 분과 초를 Pair로 반환
  return Pair(minutes, seconds)
}
