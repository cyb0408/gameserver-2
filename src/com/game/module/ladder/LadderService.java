package com.game.module.ladder;

import com.game.data.CopyConfig;
import com.game.data.ErrCode;
import com.game.data.LadderCfg;
import com.game.data.Response;
import com.game.event.InitHandler;
import com.game.module.activity.ActivityConsts;
import com.game.module.copy.CopyExtension;
import com.game.module.goods.GoodsEntry;
import com.game.module.goods.GoodsService;
import com.game.module.log.LogConsume;
import com.game.module.mail.MailService;
import com.game.module.player.Player;
import com.game.module.player.PlayerData;
import com.game.module.player.PlayerService;
import com.game.module.serial.SerialDataService;
import com.game.module.task.Task;
import com.game.module.task.TaskService;
import com.game.module.title.TitleConsts;
import com.game.module.title.TitleService;
import com.game.params.IntParam;
import com.game.params.ListParam;
import com.game.params.Reward;
import com.game.params.copy.CopyResult;
import com.game.params.ladder.LadderMemberVO;
import com.game.params.ladder.LadderRecordVO;
import com.game.params.ladder.LadderVO;
import com.game.params.rank.LadderRankVO;
import com.game.params.scene.SkillHurtVO;
import com.game.params.worldboss.MonsterHurtVO;
import com.game.util.ConfigData;
import com.game.util.JsonUtils;
import com.game.util.TimeUtil;
import com.game.util.TimerService;
import com.google.common.collect.Maps;
import com.server.SessionManager;
import com.server.util.ServerLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by lucky on 2017/9/13.
 * 排位赛
 */
@Service
public class LadderService implements InitHandler {

    private final static int TYPE_1 = 1; //战斗胜利但段位不变
    private final static int TYPE_2 = 2; //战斗失败但段位不变
    private final static int TYPE_3 = 3; //战斗胜利而且段位提升
    private final static int TYPE_4 = 4; //战斗失败并且段位下降
    private final static int TYPE_5 = 5; //战斗失败但不扣除积分
    private final static int TYPE_6 = 6; //战斗失败但不扣除积分
    private final static int MATCH_TIMES = 5;

    private final static int LADDER_COPY = 1300101;
    //大段里面小段为
    private final static int STAGE_NUM = 3;
    //段位信息
    private final static int CMD_LADDER_INFO = 6007;
    //匹配失败
    private final static int CMD_MATCHING_FAIL = 6006;
    //匹配成功
    private final static int CMD_START_GAME = 6004;
    //个人排位赛信息
    private final static int CMD_INFO = 6001;
    //加载完毕
    private final static int CMD_LOAD_OVER = 6008;
    //取消战斗
    private final static int CMD_GAME_OVER = 6010;


    @Autowired
    private TimerService timerService;
    @Autowired
    private SerialDataService serialDataService;
    @Autowired
    private PlayerService playerService;
    @Autowired
    private MailService mailService;
    @Autowired
    private GoodsService goodsService;
    @Autowired
    private TitleService titleService;
    @Autowired
    private TaskService taskService;

    //玩家ID -- rank
    private final Map<Integer, Integer> ladderRank = new ConcurrentHashMap<>();
    private final static int MAX_RANK = 200;
    /**
     * ID生成
     */
    private final AtomicInteger IdGen = new AtomicInteger(100);
    /**
     * 匹配房间,玩家ID 对房间
     */
    private final Map<Integer, Room> allRooms = new ConcurrentHashMap<>();
    /**
     * 需要检查是否超时的房间，战斗超时，加载超时等
     */
    private final Map<Integer, Room> checkTimeoutRooms = new ConcurrentHashMap<>();
    /**
     * 房间类型--- <房间人数---房间>
     */
    private final Map<Integer, Map<Integer, List<Room>>> matchRooms = new ConcurrentHashMap<>();
    /**
     * 排行榜信息
     */
    private final ListParam<LadderRankVO> LADDER_RANK = new ListParam();

    private boolean debug = false;

    @Override
    public void handleInit() {
        //5S定时
        timerService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    doMatching();
                } catch (Exception e) {
                    ServerLogger.err(e, "排位赛匹配异常");
                }
            }
        }, 10, 10, TimeUnit.SECONDS);

        //1S定时
        timerService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    doCheckTimeOut();
                } catch (Exception e) {
                    ServerLogger.err(e, "排位赛超时检测异常");
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void removeRoom(int roomId) {
        checkTimeoutRooms.remove(roomId);
        allRooms.remove(roomId);

        ServerLogger.info("allRooms's size = " + allRooms.size());
        ServerLogger.info("checkTimeoutRooms's size = " + checkTimeoutRooms.size());
    }

    private void doCheckTimeOut() {
        List<RoomPlayer> list = new ArrayList<>();
        CopyConfig cfg = ConfigData.getConfig(CopyConfig.class, LADDER_COPY);
        for (Room room : checkTimeoutRooms.values()) {
            int currentTime = (int) (System.currentTimeMillis() / 1000);
            if (!room.fightFlag) { //加载超时匹配
                if (currentTime >= room.startLoadTime + 30) {
                    gameOver(room);
                }
            } else { //战斗时间检查
                if (currentTime >= room.startFightTime + cfg.timeLimit) {
                    list.clear();
                    list.addAll(room.roomPlayers.values());
                    if (list.size() < 2) { //保护
                        IntParam param = new IntParam();
                        param.param = Response.SUCCESS;
                        for (RoomPlayer roomPlayer : list) {
                            SessionManager.getInstance().sendMsg(CMD_GAME_OVER, param, roomPlayer.getPlayerId());
                        }

                        removeRoom(room.id);
                        ServerLogger.warn("ladder time out ====" + room.id);
                        return;
                    }

                    RoomPlayer rp1 = list.get(0);
                    RoomPlayer rp2 = list.get(1);
                    Player winPlayer = playerService.getPlayer(rp1.getPlayerId());
                    Player failPlayer = playerService.getPlayer(rp2.getPlayerId());
                    float rate1 = rp1.getHp() / (winPlayer.getHp() * 1.0f);
                    float rate2 = rp2.getHp() / (failPlayer.getHp() * 1.0f);
                    if (rate2 > rate1) {
                        winPlayer = playerService.getPlayer(rp2.getPlayerId());
                        failPlayer = playerService.getPlayer(rp1.getPlayerId());
                    }

                    if (Math.abs(rate1 - rate2) < 0.0001) { // 误差小于这么多，就判断2个相等，判定2个都失败
                        onAllFail(room, winPlayer, failPlayer);
                    } else { //有输赢
                        onGameOver(room, winPlayer, failPlayer);
                    }
                }
            }

        }
    }

    /**
     * 匹配
     */
    private void doMatching() {
        for (Room source : allRooms.values()) {
            if (source.matchFlag //已经匹配成功
                    || source.exitFlag  //玩家退出游戏
                    || source.fightFlag) { //房间正在战斗，check source room
                continue;
            }

            for (int i = 0; i < MATCH_TIMES; i++) {
                for (Room target : allRooms.values()) {
                    if (source.matchFlag //已经匹配成功
                            || source.exitFlag //玩家退出游戏
                            || source.fightFlag) { // 房间正在战斗，check source room again
                        break;
                    }

                    if (source.id == target.id) {
                        continue;
                    }

                    if (target.exitFlag //玩家退出游戏
                            || target.matchFlag  //已经匹配成功
                            || target.fightFlag) { //房间正在战斗，check target room
                        continue;
                    }

                    if (debug) {
                        startGame(source, target);
                        break;
                    }

                    //int minScore = (int) (source.score - ((source.score + 100) * (0.1 + 0.05 * i)));
                    //int maxScore = (int) (source.score + ((source.score + 100) * (0.1 + 0.05 * i)));
                    int minScore = (int) (source.score - (source.level * 100 * (0.4 + 0.2 * i)));
                    int maxScore = (int) (source.score + (source.level * 100 * (0.4 + 0.2 * i)));
                    if (target.score >= minScore && target.score <= maxScore) {
                        startGame(source, target);
                        break;
                    }
                }
            }
            if (source.time == 3) {
                matchingFail(source);
            }
            source.time += 1;
        }
    }

    public boolean checkLadder(int playerId) {
        Player player = playerService.getPlayer(playerId);
        Room room = allRooms.get(player.getRoomId());
        if (room == null) {
            ServerLogger.info("ladder fight check fail roomId = " + player.getRoomId());
            player.setRoomId(0);
            return false;
        }

        if (room.roomPlayers.size() < 2) {
            ServerLogger.info("ladder fight check fail room size  = " + room.roomPlayers.size());
            return false;
        }
        return true;
    }

    /**
     * 匹配失败
     *
     * @param source
     */
    private void matchingFail(Room source) {
        allRooms.remove(source.id);
        IntParam param = new IntParam();
        for (int playerId : source.roomPlayers.keySet()) {
            Player player = playerService.getPlayer(playerId);
            player.setRoomId(0);
            SessionManager.getInstance().sendMsg(CMD_MATCHING_FAIL, param, playerId);
        }
    }

    /**
     * 匹配失败，进行机器人匹配
     *
     * @param source
     */
    private void matchingRobot(Room source) {
        allRooms.remove(source.id);
        IntParam param = new IntParam();
        for (int playerId : source.roomPlayers.keySet()) {
            Player player = playerService.getPlayer(playerId);
            player.setRoomId(0);
            SessionManager.getInstance().sendMsg(CMD_MATCHING_FAIL, param, playerId);
        }
    }

    /**
     * 开始游戏
     *
     * @param source
     * @param target
     */
    private void startGame(Room source, Room target) {
        if (source.exitFlag || target.exitFlag) { //check again
            matchingFail(source);
            matchingFail(target);
            return;
        }

        allRooms.remove(target.id);
        source.roomPlayers.putAll(target.roomPlayers);

        if (!allRooms.containsKey(source.id) || source.roomPlayers.size() != 2) {
            matchingFail(source);
            return;
        }

        ServerLogger.info("start fight..............");
        source.matchFlag = true;
        target.matchFlag = true;
        int roomId = source.id;
        ListParam<LadderMemberVO> listParam = new ListParam<>();
        listParam.params = new ArrayList<>();
        int i = 0;
        for (int playerId : source.roomPlayers.keySet()) {
            Player player = playerService.getPlayer(playerId);
            player.setRoomId(roomId);

            Ladder ladder = serialDataService.getData().getLadderMap().get(playerId);
            int ladderLv = 1;
            if (ladder != null) {
                ladderLv = ladder.getLevel();
            }

            LadderMemberVO vo = new LadderMemberVO();
            vo.level = player.getLev();
            vo.name = player.getName();
            vo.vocation = player.getVocation();
            vo.ladderLevel = ladderLv;
            vo.playerId = playerId;
            i += 1;
            vo.team = i;
            player.roomTeamId = i;

            listParam.params.add(vo);
        }

        for (int playerId : source.roomPlayers.keySet()) {
            SessionManager.getInstance().sendMsg(CMD_START_GAME, listParam, playerId);
        }

        if (source.startLoadTime == 0) {
            //开始加载
            source.startLoadTime = (int) (System.currentTimeMillis() / 1000);
            if (!checkTimeoutRooms.containsKey(source.id)) {
                checkTimeoutRooms.put(source.id, source);
            }
        }
    }

    /**
     * 获取排位赛相关信息
     *
     * @param playerId
     */
    public LadderVO getLadderInfo(int playerId) {
        Ladder ladder = serialDataService.getData().getLadderMap().get(playerId);
        if (ladder == null) {
            ladder = new Ladder();
            ladder.setLevel(1);
            ladder.setScore(0);
            ladder.setPlayerId(playerId);
            serialDataService.getData().getLadderMap().put(playerId, ladder);
        }
        PlayerData data = playerService.getPlayerData(playerId);
        LadderVO vo = new LadderVO();
        vo.score = ladder.getScore();
        vo.level = ladder.getLevel();
        vo.winTimes = ladder.getWinTimes();
        vo.continuityWinTimes = ladder.getMaxContinuityWinTimes();
        vo.honorPoint = data.getHonorPoint();
        vo.fightTimes = ladder.getFightTimes();
        return vo;
    }

    // 刷新数据
    public void refresh(int playerId) {
        SessionManager.getInstance().sendMsg(CMD_INFO, getLadderInfo(playerId), playerId);
    }

    /**
     * 段位更新
     *
     * @param playerId
     * @param ladder
     */
    public void pushLadderInfo(int playerId, Ladder ladder) {
        /*LadderVO vo = new LadderVO();
        vo.score = ladder.getScore();
        vo.level = ladder.getLevel();*/
        IntParam vo = new IntParam();
        SessionManager.getInstance().sendMsg(CMD_LADDER_INFO, vo, playerId);
    }

    /**
     * 加载完
     *
     * @param playerId
     */
    public void loadingOver(int playerId) {
        Player player = playerService.getPlayer(playerId);
        Room room = allRooms.get(player.getRoomId());
        if (room == null) {
            ServerLogger.info("loading over fail ,,,roomId == " + player.getRoomId());
            return;
        }

        if (room.loadingCount.incrementAndGet() >= room.roomPlayers.size()) {
            IntParam param = new IntParam();
            param.param = Response.SUCCESS;
            room.fightFlag = true;
            for (int id : room.roomPlayers.keySet()) {
                SessionManager.getInstance().sendMsg(CMD_LOAD_OVER, param, id);
                Map<Integer, int[]> condParams = Maps.newHashMap();
                condParams.put(Task.FINISH_JOIN_PK, new int[]{2, 1});
                condParams.put(Task.TYPE_PASS_COPY_SINGLE, new int[]{1300101, 1});
                taskService.doTask(id, condParams);
            }
            //开始战斗
            room.startFightTime = (int) (System.currentTimeMillis() / 1000);
        }

        if (!checkTimeoutRooms.containsKey(room.id)) {
            checkTimeoutRooms.put(room.id, room);
        }
    }

    /**
     * 开始匹配
     *
     * @param playerId
     */
    public IntParam startMatching(int playerId) {
        IntParam param = new IntParam();
        if (!checkOpen()) {
            param.param = Response.TEAM_TIME_OVER;
            return param;
        }
        Ladder ladder = serialDataService.getData().getLadderMap().get(playerId);
        if (ladder == null) {
            ladder = new Ladder();
            ladder.setLevel(1);
            ladder.setPlayerId(playerId);
            serialDataService.getData().getLadderMap().put(playerId, ladder);
        }
        Player player = playerService.getPlayer(playerId);

        if (player.getRoomId() != 0 && allRooms.containsKey(player.getRoomId())) { //连续请求?正在匹配中
            param.param = Response.ERR_PARAM;
            return param;
        }

        Room room = new Room(IdGen.getAndIncrement(), ladder.getScore(), 0, ladder.getLevel());
        room.roomPlayers.put(playerId, new RoomPlayer(player.getHp(), player.getPlayerId()));
        allRooms.put(room.id, room);

        ServerLogger.info("matching room size === " + allRooms.size() + " roomId = " + room.id);
        player.setRoomId(room.id);
        param.param = Response.SUCCESS;
        return param;
    }

    /**
     * 取消匹配
     *
     * @param playerId
     */
    public IntParam cancelMatching(int playerId) {
        Player player = playerService.getPlayer(playerId);
        Room room = allRooms.remove(player.getRoomId());
        ServerLogger.info("matching room size === " + allRooms.size());
        player.setRoomId(0);
        if (room != null) {
            room.exitFlag = true;
        }
        IntParam param = new IntParam();
        param.param = Response.SUCCESS;
        return param;
    }

    /**
     * 都判失败
     *
     * @param room
     * @param failPlayer1
     * @param failPlayer2
     */
    private void onAllFail(Room room, Player failPlayer1, Player failPlayer2) {
        if (room.checkHasRward()) { //检测是否领取奖励
            return;
        }
        removeRoom(room.id);
        ServerLogger.info("fight over, room size === " + allRooms.size());
        failPlayer1.setRoomId(0);
        failPlayer2.setRoomId(0);

        Ladder failLadderInfo1 = serialDataService.getData().getLadderMap().get(failPlayer1.getPlayerId());
        Ladder failLadderInfo2 = serialDataService.getData().getLadderMap().get(failPlayer2.getPlayerId());

        failLadderInfo1.setFightTimes(failLadderInfo1.getFightTimes() + 1);
        failLadderInfo1.setContinuityWinTimes(0);

        failLadderInfo2.setFightTimes(failLadderInfo2.getFightTimes() + 1);
        failLadderInfo2.setContinuityWinTimes(0);


        LadderCfg failCfg1 = ConfigData.getConfig(LadderCfg.class, failLadderInfo1.getLevel());
        LadderCfg failCfg2 = ConfigData.getConfig(LadderCfg.class, failLadderInfo2.getLevel());


        int failScore1 = failCfg1.decScore;
        int failScore2 = failCfg2.decScore;

        int oldFailScore1 = failLadderInfo1.getScore();
        int oldFailScore2 = failLadderInfo2.getScore();

        int score = oldFailScore1 - failScore1 > 0 ? oldFailScore1 - failScore1 : 0;
        failLadderInfo1.setScore(score);

        score = oldFailScore2 - failScore2 > 0 ? oldFailScore2 - failScore2 : 0;
        failLadderInfo2.setScore(score);


        LadderRecord failRecord1 = new LadderRecord();
        failRecord1.setResult(0);
        failRecord1.setOtherName(failPlayer2.getName());
        failRecord1.setScore(failScore1);

        LadderRecord failRecord2 = new LadderRecord();
        failRecord2.setResult(0);
        failRecord2.setOtherName(failPlayer1.getName());
        failRecord2.setScore(failScore2);

        calcLadderLevelUp(failPlayer1, failLadderInfo1, failRecord1, oldFailScore1);
        calcLadderLevelUp(failPlayer2, failLadderInfo2, failRecord2, oldFailScore2);

        if (failCfg1.decScore == 0) {
            failRecord1.setType(TYPE_6);
        } else if (failScore1 == 0) {
            failRecord1.setType(TYPE_5);
        }

        if (failLadderInfo1.getRecords().size() > 15) {
            failLadderInfo1.getRecords().remove(0);
        }
        failLadderInfo1.getRecords().add(failRecord1);

        if (failLadderInfo2.getRecords().size() > 15) {
            failLadderInfo2.getRecords().remove(0);
        }
        failLadderInfo2.getRecords().add(failRecord2);

        buildLadderAward(failPlayer1, failCfg1, false);
        buildLadderAward(failPlayer2, failCfg2, false);
    }


    private void onGameOver(Room room, Player winPlayer, Player failPlayer) {
        if (room.checkHasRward()) { //检测是否领取奖励
            return;
        }
        removeRoom(room.id);
        ServerLogger.info("fight over, room size === " + allRooms.size());
        winPlayer.setRoomId(0);
        failPlayer.setRoomId(0);

        Ladder winLadderInfo = serialDataService.getData().getLadderMap().get(winPlayer.getPlayerId());
        Ladder failLadderInfo = serialDataService.getData().getLadderMap().get(failPlayer.getPlayerId());

        winLadderInfo.setWinTimes(winLadderInfo.getWinTimes() + 1);
        winLadderInfo.setFightTimes(winLadderInfo.getFightTimes() + 1);
        winLadderInfo.setContinuityWinTimes(winLadderInfo.getContinuityWinTimes() + 1);
        if (winLadderInfo.getMaxContinuityWinTimes() < winLadderInfo.getContinuityWinTimes()) {
            winLadderInfo.setMaxContinuityWinTimes(winLadderInfo.getContinuityWinTimes());
        }

        failLadderInfo.setFightTimes(failLadderInfo.getFightTimes() + 1);
        failLadderInfo.setContinuityWinTimes(0);

        int winStage = winLadderInfo.getLevel() / STAGE_NUM + 1;
        int failStage = failLadderInfo.getLevel() / STAGE_NUM + 1;

        LadderCfg winCfg = ConfigData.getConfig(LadderCfg.class, winLadderInfo.getLevel());
        LadderCfg failCfg = ConfigData.getConfig(LadderCfg.class, failLadderInfo.getLevel());

        int winScore = winCfg.addScore;
        int failScore = failCfg.decScore;
        if (winStage - failStage >= ConfigData.globalParam().PkProtect) { //段位保护
            failScore = 0;
        }
        if (winStage < failStage - 1) { //加分加成，同时失败减分加成
            winScore = Math.round(winScore * (1 + ConfigData.globalParam().PkAdd));
            failScore = Math.round(failScore * (1 + ConfigData.globalParam().PkDec));
        }

        int oldWinScore = winLadderInfo.getScore();
        int oldFailScore = failLadderInfo.getScore();

        winLadderInfo.setScore(oldWinScore + winScore);
        int score = oldFailScore - failScore > 0 ? oldFailScore - failScore : 0;
        failLadderInfo.setScore(score);

        LadderRecord winRecord = new LadderRecord();
        winRecord.setResult(1);
        winRecord.setOtherName(failPlayer.getName());
        winRecord.setScore(winScore);

        LadderRecord failRecord = new LadderRecord();
        failRecord.setResult(0);
        failRecord.setOtherName(winPlayer.getName());
        failRecord.setScore(failScore);

        calcLadderLevelUp(winPlayer, winLadderInfo, winRecord, oldWinScore);
        calcLadderLevelUp(failPlayer, failLadderInfo, failRecord, oldFailScore);

        if (failCfg.decScore == 0) {
            failRecord.setType(TYPE_6);
        } else if (failScore == 0) {
            failRecord.setType(TYPE_5);
        }

        if (winLadderInfo.getRecords().size() > 15) {
            winLadderInfo.getRecords().remove(0);
        }
        winLadderInfo.getRecords().add(winRecord);

        if (failLadderInfo.getRecords().size() > 15) {
            failLadderInfo.getRecords().remove(0);
        }
        failLadderInfo.getRecords().add(failRecord);

        buildLadderAward(winPlayer, winCfg, true);
        buildLadderAward(failPlayer, failCfg, false);
    }

    /**
     * 结算奖励
     *
     * @param player
     * @param conf
     * @param victory
     */
    private void buildLadderAward(Player player, LadderCfg conf, boolean victory) {
        CopyResult ladderAward = new CopyResult();
        ladderAward.rewards = new ArrayList<>();
        List<GoodsEntry> items = new ArrayList<>();
        ladderAward.victory = victory;
        float rate = 1f;
        if (!victory) {
            rate = 0.5f;
        }
        for (int i = 0; i < conf.PkReward.length; i += 2) {
            Reward reward = new Reward();
            int id = conf.PkReward[i];
            int count = Math.round(conf.PkReward[i + 1] * rate);
            reward.id = id;
            reward.count = count;
            ladderAward.rewards.add(reward);

            items.add(new GoodsEntry(id, count));
        }
        ladderAward.id = player.getCopyId();
        goodsService.addRewards(player.getPlayerId(), items, LogConsume.LADDER_AWARD);
        SessionManager.getInstance().sendMsg(CopyExtension.TAKE_COPY_REWARDS, ladderAward, player.getPlayerId());
    }


    /**
     * 计算升级或者降级及战报
     *
     * @param player
     * @param ladderInfo
     */
    private void calcLadderLevelUp(Player player, Ladder ladderInfo, LadderRecord record, int oldScore) {
        int newLevel = 1;
        int level = ladderInfo.getLevel();
        int totalScore = ladderInfo.getScore();
        int maxLevel = ConfigData.getConfigs(LadderCfg.class).size();
        for (int i = 1; i <= maxLevel - 1; i++) {
            LadderCfg cfg = ConfigData.getConfig(LadderCfg.class, i);
            if (totalScore < cfg.score) {
                break;
            }
            newLevel += 1;
            totalScore -= cfg.score;
        }

        if (newLevel != level) {
            ladderInfo.setLevel(newLevel);
            taskService.doTask(player.getPlayerId(), Task.TYPE_LADDER, newLevel);
        }

        if (newLevel != level || oldScore != ladderInfo.getScore()) {
            pushLadderInfo(player.getPlayerId(), ladderInfo);
        }

        if (newLevel == level) {
            if (record.getResult() == 1) {
                record.setType(TYPE_1);
            } else {
                record.setType(TYPE_2);
            }
        } else if (newLevel > level) {
            record.setType(TYPE_3);
        } else if (newLevel < level) {
            record.setType(TYPE_4);
        }
        record.setLevel(newLevel);
    }


    private void gameOver(Room room) {
        IntParam param = new IntParam();
        param.param = Response.SUCCESS;
        for (int id : room.roomPlayers.keySet()) {
            SessionManager.getInstance().sendMsg(CMD_GAME_OVER, param, id);
            Player p = playerService.getPlayer(id);
            p.setRoomId(0);
        }
        removeRoom(room.id);
    }

    /**
     * 掉线，退出处理
     *
     * @param playerId
     */
    public void onLogout(int playerId) {
        try {
            Player failPlayer = playerService.getPlayer(playerId);
            Room room = allRooms.get(failPlayer.getRoomId());
            if (room == null) {
                return;
            }
            room.exitFlag = true;
            if (!room.fightFlag) {
                gameOver(room);
                return;
            }

            Player winPlayer = null;
            for (int id : room.roomPlayers.keySet()) {
                if (id != playerId) {
                    winPlayer = playerService.getPlayer(id);
                }
            }
            if (winPlayer == null) {
                removeRoom(room.id);
                return;
            }
            onGameOver(room, winPlayer, failPlayer);
        } catch (Exception e) {
            ServerLogger.err(e, "排位赛退出异常");
        }
    }

    public List<Ladder> ladderSort() {
        if (serialDataService.getData() != null) {
            ladderRank.clear();
            List<Ladder> list = new ArrayList<>(serialDataService.getData().getLadderMap().values());
            Collections.sort(list, COMPARATOR);
            LADDER_RANK.params = new ArrayList<>();
            for (Ladder ladder : list) {
                Player player = playerService.getPlayer(ladder.getPlayerId());
                if (player == null) {
                    continue;
                }

                LadderRankVO vo = new LadderRankVO();
                vo.playerId = ladder.getPlayerId();
                vo.name = player.getName();
                vo.level = ladder.getLevel();
                vo.vocation = player.getVocation();
                vo.score = ladder.getScore();
                vo.levNum = ladder.getLevel();
                vo.playerId = ladder.getPlayerId();
                vo.fightingValue = player.getFight();
                vo.vip = player.getVip();
                LADDER_RANK.params.add(vo);
            }

            return list;
        }
        return null;
    }

    public ListParam getLadderRank() {
        return LADDER_RANK;
    }

    public int getRank(int playerId) {
        Integer rank = ladderRank.get(playerId);
        if (rank == null) {
            rank = 0;
        }
        return rank;
    }

    /**
     * 检测活动是否开启
     *
     * @return
     */
    public boolean checkOpen() {
        return TimeUtil.checkTimeIn(ConfigData.globalParam().PKTime);
    }


    private static final int CMD_MONSTER_INFO = 4910; //同步怪物相关信息

    // 玩家技能处理
    public void handleSkillHurt(Player player, SkillHurtVO hurtVO) {
        Room room = allRooms.get(player.getRoomId());
        if (room != null && hurtVO.targetType == 0) {
            RoomPlayer roomPlayer = room.getRoomPlayer(hurtVO.targetId);
            roomPlayer.decreaseHp(hurtVO.hurtValue);

            MonsterHurtVO vo = new MonsterHurtVO();
            vo.actorId = hurtVO.targetId;
            vo.curHp = roomPlayer.getHp();
            vo.hurt = hurtVO.hurtValue;
            vo.isCrit = hurtVO.isCrit;
            vo.type = 0;
            for (int playerId : room.roomPlayers.keySet()) {
                SessionManager.getInstance().sendMsg(CMD_MONSTER_INFO, vo, playerId);
            }
            if (roomPlayer.checkDeath()) { //屎了?
                ServerLogger.info("death = " + JsonUtils.object2String(roomPlayer));
                ServerLogger.info("win = " + player.getPlayerId());
                Player winPlayer = null;
                Player failPlayer = null;
                for (int id : room.roomPlayers.keySet()) {
                    if (id == roomPlayer.getPlayerId()) {
                        failPlayer = playerService.getPlayer(id);
                    } else {
                        winPlayer = playerService.getPlayer(id);
                    }
                }
                if (failPlayer == null) {
                    return;
                }
                onGameOver(room, winPlayer, failPlayer);
            }
        }
    }


    /**
     * 每周一定时奖励
     */
    public void weeklyAward() {
        ServerLogger.warn("send ladder reward...........");
        String awardTitle = ConfigData.getConfig(ErrCode.class, Response.LADDER_MAIL_TITLE).tips;
        String awardContent = ConfigData.getConfig(ErrCode.class, Response.LADDER_MAIL_CONTENT).tips;
        List<GoodsEntry> rewards = new ArrayList<>();
        List<Ladder> list = ladderSort();
        ladderRank.clear();
        if (list != null) {
            int k = 0;
            for (Ladder ladder : list) {
                LadderCfg cfg = ConfigData.getConfig(LadderCfg.class, ladder.getLevel());
                if (cfg == null) {
                    cfg = ConfigData.getConfig(LadderCfg.class, ladder.getLevel() - 1);
                    if (cfg == null) {
                        ServerLogger.warn("send ladder reward error,level = " + ladder.getLevel());
                        continue;
                    }
                }
                rewards.clear();
                for (int i = 0; i < cfg.FinalReward.length; i += 2) {
                    rewards.add(new GoodsEntry(cfg.FinalReward[i], cfg.FinalReward[i + 1]));
                }
                String content = String.format(awardContent, cfg.name);

                ladder.setLastTime(0);
                ladder.setLevel(1);
                ladder.setScore(0);
                ladder.setWinTimes(0);
                ladder.setMaxContinuityWinTimes(0);
                ladder.setFightTimes(0);
                ladder.getRecords().clear();
                k++;
                if (k < MAX_RANK) {
                    titleService.complete(ladder.getPlayerId(), TitleConsts.LADDER, k, ActivityConsts.UpdateType.T_VALUE);
                    ladderRank.put(ladder.getPlayerId(), k);
                }
                mailService.sendSysMail(awardTitle, content, rewards, ladder.getPlayerId(), LogConsume.LADDER_AWARD);
            }
        }
    }

    /**
     * 排序
     */
    private static final Comparator<Ladder> COMPARATOR = new Comparator<Ladder>() {
        @Override
        public int compare(Ladder o1, Ladder o2) {
            if (o1.getScore() == o2.getScore()) {
                return (int) (o2.getLastTime() - o1.getLastTime());
            }
            return o2.getScore() - o1.getScore();
        }
    };


    /**
     * 获取战报
     *
     * @param playerId
     * @return
     */
    public ListParam<LadderRecordVO> getRecords(int playerId) {
        Ladder ladder = serialDataService.getData().getLadderMap().get(playerId);
        ListParam<LadderRecordVO> recordVOListParam = new ListParam<>();
        recordVOListParam.params = new ArrayList<>();

        for (LadderRecord record : ladder.getRecords()) {
            LadderRecordVO vo = new LadderRecordVO();

            vo.name = record.getOtherName();
            vo.result = record.getResult();
            vo.score = record.getScore();
            vo.type = record.getType();
            vo.rankId = record.getLevel();
            recordVOListParam.params.add(vo);
        }

        return recordVOListParam;
    }

    public void gmSort() {
        ladderSort();
    }

    public void gmAddScore(int playerId, int score) {
        Ladder ladderInfo = serialDataService.getData().getLadderMap().get(playerId);
        if (ladderInfo == null) {
            ladderInfo = new Ladder();
            ladderInfo.setScore(0);
            serialDataService.getData().getLadderMap().put(playerId, ladderInfo);
        }

        int newLevel = 1;
        int totalScore = score + ladderInfo.getScore();
        int maxLevel = ConfigData.getConfigs(LadderCfg.class).size();
        for (int i = 1; i <= maxLevel - 1; i++) {
            LadderCfg cfg = ConfigData.getConfig(LadderCfg.class, i);
            if (totalScore < cfg.score) {
                break;
            }
            newLevel += 1;
            totalScore -= cfg.score;
        }

        ladderInfo.setLevel(newLevel);
        ladderInfo.setScore(score + ladderInfo.getScore());
    }

    public void gmDebug() {
        debug = true;
    }
}
