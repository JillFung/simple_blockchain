package com.blockchain.core.net.handler;


import com.blockchain.bean.block.Block;
import com.blockchain.bft.SimpleBft;
import com.blockchain.bft.SyncBlockBftHandler;
import com.blockchain.checker.CheckResult;
import com.blockchain.common.App;
import com.blockchain.common.ApplicationContextProvider;
import com.blockchain.core.BlockExecutor;
import com.blockchain.core.net.AbstractMessageHandler;
import com.blockchain.core.net.MessageBuilder;
import com.blockchain.core.net.MessagePacket;
import com.blockchain.core.net.Sender;
import com.blockchain.manmger.BlockManager;
import com.blockchain.server.CheckService;
import com.blockchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tio.core.ChannelContext;

/**
 * Created by: Yumira.
 * Created on: 2018/7/28-上午1:27.
 * Description: 处理同步区块的消费者类
 */
@Component
public class SyncBlockResponseHandler extends AbstractMessageHandler<Block> {

    @Autowired
    private BlockManager blockManager;
    @Autowired
    private CheckService checkService;
    @Autowired
    private BlockExecutor blockExecutor;
    /**
     * 暴露当前区块同步的进度，供外部调用
     */
    private Block currentSyncBlock;

    /**
     * 初始化pbft算法处理器
     */
    private SimpleBft bft = initBft();

    @Override
    public Class<Block> parseBodyClass() {
        return Block.class;
    }

    /**
     * 这里要处理高并发的情况，多线程执行会导致交易时间校验错误，即便数据库操作是线程安全的
     */
    @Override
    public Object handler(MessagePacket packet, Block body, ChannelContext channelContext) throws Exception {
        //并发校验
        if ((currentSyncBlock != null
                && currentSyncBlock.equals(body))//说明已经有线程正在同步该区块，其他线程不需要再继续执行
                || (body != null && currentSyncBlock != null
                && body.getBlockHeader().getTimeStamp() //或者由于网络阻塞，返回的block时间小于正在执行的block时间
                <= currentSyncBlock.getBlockHeader().getTimeStamp())
                || (body != null && currentSyncBlock == null
                && blockManager.getLastBlock() != null //当前没有block正在执行，但是返回的block时间小于本地最末尾block的时间
                && body.getBlockHeader().getTimeStamp()
                <= blockManager.getLastBlock().getBlockHeader().getTimeStamp())){
            return null; //直接跳出
        }
        currentSyncBlock = body;
        if (body != null){
            logger.info("Response: 来自 " + channelContext.getServerNode() + " 的同步消息，需要同步的Block为：" + JsonUtil.toJSONString(body));
            //校验新区块的合法性
            CheckResult confirm = checkService.checkBlock(body);
            if (confirm.getCode() == CheckService.OK){
                //同步本地区块，线程安全执行
                blockManager.addBlock(body);
                //执行区块体中的交易和账户信息
                blockExecutor.execute(body);
                //递归发送同步消息，直到更新到最长的链
                Sender.sendGroup(MessageBuilder.buildSyncBlockPacket(body.getHash()));
            }else {
                logger.info("同步失败，区块校验不合法 错误信息: " + JsonUtil.toJSONString(confirm));
            }
        }else {
            //如果为空，说明已经同步到最新区块了
            logger.info("相较于 " + channelContext.getServerNode() + " ，本地已是最新块了\n");
            // 用PBFT共识算法来判断是否完成同步
            bft.receiveEvent(blockManager.getLastBlock());
        }
        return null;
    }


    /**
     * 这里用拜占庭容错算法校验是否同步到最后一个区块
     * 当前已连接节点数N，如果收到的相同区块hash数量，满足 (2N+1)/3 即区块同步成功
     */
    private SimpleBft initBft(){
        return new SimpleBft<Block>() {
            @Override
            public boolean equals(Block object1, Block object2) {
                if (object1.getHash().equals(object2.getHash())){
                    return true;
                }
                return false;
            }

            @Override
            public void onAgreement() {
                if (!App.isSyncComplete) {
                    logger.info("bft共识成功，本地已更新到最新区块，随时可以开始挖矿");
                    App.isSyncComplete = true;
                }
            }

            @Override
            public void onAgreeFail() {
                logger.info("bft共识失败，有恶意节点存在，请稍候再试");
                App.isSyncComplete = false;
            }
        };
    }

    public Block getCurrentSyncBlock() {
        return currentSyncBlock;
    }

}
