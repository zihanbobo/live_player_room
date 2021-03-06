package com.zjy.handler;

import com.alibaba.fastjson.JSONObject;
import com.zjy.JwtUtil.JwtUtils;
import com.zjy.entity.UserInfo;
import com.zjy.proto.ChatCode;
import com.zjy.redisUtil.RedisShardedPoolUtil;
import com.zjy.util.Constants;
import com.zjy.util.FFMPEG;
import com.zjy.util.NettyUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @description 处理请求认证和分发消息
 */
public class UserAuthHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger logger = LoggerFactory.getLogger(UserAuthHandler.class);

    private WebSocketServerHandshaker handshaker;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocket(ctx, (WebSocketFrame) msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent evnet = (IdleStateEvent) evt;
            // 判断Channel是否读空闲, 读空闲时移除Channel
            if (evnet.state().equals(IdleState.READER_IDLE)) {
                final String remoteAddress = NettyUtil.parseChannelRemoteAddr(ctx.channel());
                logger.warn("NETTY SERVER PIPELINE: IDLE exception [{}]", remoteAddress);
                UserInfoManager.removeChannel(ctx.channel());
                UserInfoManager.broadCastInfo(ChatCode.SYS_USER_COUNT,UserInfoManager.getAuthUserCount());
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess() || !"websocket".equals(request.headers().get("Upgrade"))) {
            logger.warn("protobuf don't support websocket");
            ctx.channel().close();
            return;
        }
        WebSocketServerHandshakerFactory handshakerFactory = new WebSocketServerHandshakerFactory(
                Constants.WEBSOCKET_URL, null, true);
        handshaker = handshakerFactory.newHandshaker(request);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            // 动态加入websocket的编解码处理
            handshaker.handshake(ctx.channel(), request);
            UserInfo userInfo = new UserInfo();
            userInfo.setAddr(NettyUtil.parseChannelRemoteAddr(ctx.channel()));
            // 存储已经连接的Channel
            UserInfoManager.addChannel(ctx.channel());
        }
    }

    private void handleWebSocket(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // 判断是否关闭链路命令
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            UserInfoManager.removeChannel(ctx.channel());
            return;
        }
        // 判断是否Ping消息
        if (frame instanceof PingWebSocketFrame) {
            logger.info("ping message:{}", frame.content().retain());
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        // 判断是否Pong消息
        if (frame instanceof PongWebSocketFrame) {
            logger.info("pong message:{}", frame.content().retain());
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        // 本程序目前只支持文本消息
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(frame.getClass().getName() + " frame type not supported");
        }
        String message = ((TextWebSocketFrame) frame).text();
        JSONObject json = JSONObject.parseObject(message);
        int code = json.getInteger("code");
        Channel channel = ctx.channel();
        switch (code) {
            case ChatCode.PING_CODE:
            case ChatCode.PONG_CODE:
                UserInfoManager.updateUserTime(channel);
//                UserInfoManager.sendPong(ctx.channel());
                logger.info("receive pong message, address: {}",NettyUtil.parseChannelRemoteAddr(channel));
                return;
            case ChatCode.AUTH_CODE:
                String token = json.getString("token");
                JSONObject redisInfo = null;
                try {
                    redisInfo = JSONObject.parseObject(RedisShardedPoolUtil.get(token));//和redis里面的信息比对
                }catch (Exception e){
                    UserInfoManager.sendInfo(channel,ChatCode.SYS_AUTH_STATE,false);
                    return;
                }
                System.out.println(token);
                if (StringUtils.isBlank(token) || redisInfo == null){
                    UserInfoManager.sendInfo(channel,ChatCode.SYS_AUTH_STATE,false);
                    return;
                }
                //验证登陆
                Map<String, Object> infoMap = null;
                try {
                    infoMap = JwtUtils.parserJavaWebToken(token);
                }catch (Exception e){
                    UserInfoManager.sendInfo(channel,ChatCode.SYS_AUTH_STATE,false);
                    return;
                }
                if (infoMap == null
                        || !infoMap.containsKey("nickName")
                        || !infoMap.containsKey("loginName")
                        || infoMap.get("nickName") == null
                        || !StringUtils.equals((String)infoMap.get("nickName"),redisInfo.getString("nickName"))
                ){
                    UserInfoManager.sendInfo(channel,ChatCode.SYS_AUTH_STATE,false);
                    return;
                }
                String nickName = (String) infoMap.get("nickName");

                boolean isSuccess = UserInfoManager.saveUser(channel, nickName);
                UserInfoManager.sendInfo(channel,ChatCode.SYS_AUTH_STATE,isSuccess);
                if (isSuccess) {
                    UserInfoManager.broadCastInfo(ChatCode.SYS_USER_COUNT,UserInfoManager.getAuthUserCount());
                }
                return;
            case ChatCode.SYS_ONLINE_CHAT_MANAGE:
                Map<String,String> res = new HashMap<>();
                res.put("code",String.valueOf(ChatCode.SYS_ONLINE_CHAT_MANAGE));
                if (json.get("nickName") != null && json.get("type") !=null && StringUtils.equals(json.get("type").toString(),"0")){ //禁言
                    UserInfoManager.bannedByNickName(json.getString("nickName"), false);
                    res.put("mess","解禁成功");
                }else if (json.get("nickName") != null && json.get("type") !=null && StringUtils.equals(json.get("type").toString(),"1")){
                    UserInfoManager.bannedByNickName(json.getString("nickName"), true);
                    res.put("mess","禁言成功");
                }else {
                    res.put("mess","指令错误");
                }
                channel.writeAndFlush(new TextWebSocketFrame(JSONObject.toJSONString(res)));
                return;
            case ChatCode.MESS_CODE: //普通的消息留给MessageHandler处理
                break;
            case ChatCode.SYS_ONLINE_MESSAGE://开启直播命令
                UserInfo userInfo = UserInfoManager.getUserInfo(channel);
                if (!userInfo.isAdmin()){
                    Map<String,String> res1 = new HashMap<>();
                    res1.put("code",String.valueOf(ChatCode.SYS_OTHER_INFO));
                    res1.put("mess","你没有权限开启直播");
                    channel.writeAndFlush(new TextWebSocketFrame(JSONObject.toJSONString(res1)));
                    return;
                }
                if (json.get("type") !=null && StringUtils.equals(json.get("type").toString(),"0")){
                    UserInfoManager.sendLiveCmdInfo("开始关闭直播");
                    if (FFMPEG.TOOLS.getTools().close()){
                        UserInfoManager.sendLiveCmdInfo("关闭成功");
                    }else {
                        UserInfoManager.sendLiveCmdInfo("关闭失败");
                    }
                }else if (json.get("type") !=null && StringUtils.equals(json.get("type").toString(),"1")){
                    if (json.get("videoPath") != null){
                        UserInfoManager.sendLiveCmdInfo("开始开启直播");
                        new Thread(()->{
                            FFMPEG.TOOLS.getTools()
                                    .startPlay(json.get("videoPath")
                                            .toString(),
                                            false);
                        }).start();
                    }
                }
                return;
            default:
                logger.warn("The code [{}] can't be auth!!!", code);
                return;
        }
        //后续消息交给MessageHandler处理
        ctx.fireChannelRead(frame.retain());
    }
}
