package org.skywalking.apm.collector.agentjvm.grpc.handler;

import io.grpc.stub.StreamObserver;
import java.util.List;
import org.skywalking.apm.collector.agentjvm.worker.cpu.CpuMetricPersistenceWorker;
import org.skywalking.apm.collector.agentjvm.worker.gc.GCMetricPersistenceWorker;
import org.skywalking.apm.collector.agentjvm.worker.heartbeat.InstHeartBeatPersistenceWorker;
import org.skywalking.apm.collector.agentjvm.worker.heartbeat.define.InstanceHeartBeatDataDefine;
import org.skywalking.apm.collector.agentjvm.worker.memory.MemoryMetricPersistenceWorker;
import org.skywalking.apm.collector.agentjvm.worker.memorypool.MemoryPoolMetricPersistenceWorker;
import org.skywalking.apm.collector.core.framework.CollectorContextHelper;
import org.skywalking.apm.collector.core.util.Const;
import org.skywalking.apm.collector.core.util.TimeBucketUtils;
import org.skywalking.apm.collector.server.grpc.GRPCHandler;
import org.skywalking.apm.collector.storage.define.jvm.CpuMetricDataDefine;
import org.skywalking.apm.collector.storage.define.jvm.GCMetricDataDefine;
import org.skywalking.apm.collector.storage.define.jvm.MemoryMetricDataDefine;
import org.skywalking.apm.collector.storage.define.jvm.MemoryPoolMetricDataDefine;
import org.skywalking.apm.collector.stream.StreamModuleContext;
import org.skywalking.apm.collector.stream.StreamModuleGroupDefine;
import org.skywalking.apm.collector.stream.worker.WorkerInvokeException;
import org.skywalking.apm.collector.stream.worker.WorkerNotFoundException;
import org.skywalking.apm.network.proto.CPU;
import org.skywalking.apm.network.proto.Downstream;
import org.skywalking.apm.network.proto.GC;
import org.skywalking.apm.network.proto.JVMMetrics;
import org.skywalking.apm.network.proto.JVMMetricsServiceGrpc;
import org.skywalking.apm.network.proto.Memory;
import org.skywalking.apm.network.proto.MemoryPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author pengys5
 */
public class JVMMetricsServiceHandler extends JVMMetricsServiceGrpc.JVMMetricsServiceImplBase implements GRPCHandler {

    private final Logger logger = LoggerFactory.getLogger(JVMMetricsServiceHandler.class);

    @Override public void collect(JVMMetrics request, StreamObserver<Downstream> responseObserver) {
        int applicationInstanceId = request.getApplicationInstanceId();
        logger.debug("receive the jvm metric from application instance, id: {}", applicationInstanceId);

        StreamModuleContext context = (StreamModuleContext)CollectorContextHelper.INSTANCE.getContext(StreamModuleGroupDefine.GROUP_NAME);
        request.getMetricsList().forEach(metric -> {
            long time = TimeBucketUtils.INSTANCE.getSecondTimeBucket(metric.getTime());
            senToInstanceHeartBeatPersistenceWorker(context, applicationInstanceId, metric.getTime());
            sendToCpuMetricPersistenceWorker(context, applicationInstanceId, time, metric.getCpu());
            sendToMemoryMetricPersistenceWorker(context, applicationInstanceId, time, metric.getMemoryList());
            sendToMemoryPoolMetricPersistenceWorker(context, applicationInstanceId, time, metric.getMemoryPoolList());
            sendToGCMetricPersistenceWorker(context, applicationInstanceId, time, metric.getGcList());
        });

        responseObserver.onNext(Downstream.newBuilder().build());
        responseObserver.onCompleted();
    }

    private void senToInstanceHeartBeatPersistenceWorker(StreamModuleContext context, int applicationInstanceId,
        long heartBeatTime) {
        InstanceHeartBeatDataDefine.InstanceHeartBeat heartBeat = new InstanceHeartBeatDataDefine.InstanceHeartBeat();
        heartBeat.setId(String.valueOf(applicationInstanceId));
        heartBeat.setHeartBeatTime(heartBeatTime);
        heartBeat.setInstanceId(applicationInstanceId);
        try {
            logger.debug("send to instance heart beat persistence worker, id: {}", heartBeat.getId());
            context.getClusterWorkerContext().lookup(InstHeartBeatPersistenceWorker.WorkerRole.INSTANCE).tell(heartBeat.toData());
        } catch (WorkerInvokeException | WorkerNotFoundException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void sendToCpuMetricPersistenceWorker(StreamModuleContext context, int applicationInstanceId,
        long timeBucket, CPU cpu) {
        CpuMetricDataDefine.CpuMetric cpuMetric = new CpuMetricDataDefine.CpuMetric();
        cpuMetric.setId(timeBucket + Const.ID_SPLIT + applicationInstanceId);
        cpuMetric.setApplicationInstanceId(applicationInstanceId);
        cpuMetric.setUsagePercent(cpu.getUsagePercent());
        cpuMetric.setTimeBucket(timeBucket);
        try {
            logger.debug("send to cpu metric persistence worker, id: {}", cpuMetric.getId());
            context.getClusterWorkerContext().lookup(CpuMetricPersistenceWorker.WorkerRole.INSTANCE).tell(cpuMetric.toData());
        } catch (WorkerInvokeException | WorkerNotFoundException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void sendToMemoryMetricPersistenceWorker(StreamModuleContext context, int applicationInstanceId,
        long timeBucket, List<Memory> memories) {

        memories.forEach(memory -> {
            MemoryMetricDataDefine.MemoryMetric memoryMetric = new MemoryMetricDataDefine.MemoryMetric();
            memoryMetric.setId(timeBucket + Const.ID_SPLIT + applicationInstanceId + Const.ID_SPLIT + String.valueOf(memory.getIsHeap()));
            memoryMetric.setApplicationInstanceId(applicationInstanceId);
            memoryMetric.setHeap(memory.getIsHeap());
            memoryMetric.setInit(memory.getInit());
            memoryMetric.setMax(memory.getMax());
            memoryMetric.setUsed(memory.getUsed());
            memoryMetric.setCommitted(memory.getCommitted());
            memoryMetric.setTimeBucket(timeBucket);
            try {
                logger.debug("send to memory metric persistence worker, id: {}", memoryMetric.getId());
                context.getClusterWorkerContext().lookup(MemoryMetricPersistenceWorker.WorkerRole.INSTANCE).tell(memoryMetric.toData());
            } catch (WorkerInvokeException | WorkerNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
        });
    }

    private void sendToMemoryPoolMetricPersistenceWorker(StreamModuleContext context, int applicationInstanceId,
        long timeBucket, List<MemoryPool> memoryPools) {

        memoryPools.forEach(memoryPool -> {
            MemoryPoolMetricDataDefine.MemoryPoolMetric memoryPoolMetric = new MemoryPoolMetricDataDefine.MemoryPoolMetric();
            memoryPoolMetric.setId(timeBucket + Const.ID_SPLIT + applicationInstanceId + Const.ID_SPLIT + memoryPool.getIsHeap() + Const.ID_SPLIT + String.valueOf(memoryPool.getType().getNumber()));
            memoryPoolMetric.setApplicationInstanceId(applicationInstanceId);
            memoryPoolMetric.setPoolType(memoryPool.getType().getNumber());
            memoryPoolMetric.setHeap(memoryPool.getIsHeap());
            memoryPoolMetric.setInit(memoryPool.getInit());
            memoryPoolMetric.setMax(memoryPool.getMax());
            memoryPoolMetric.setUsed(memoryPool.getUsed());
            memoryPoolMetric.setCommitted(memoryPool.getCommited());
            memoryPoolMetric.setTimeBucket(timeBucket);
            try {
                logger.debug("send to memory pool metric persistence worker, id: {}", memoryPoolMetric.getId());
                context.getClusterWorkerContext().lookup(MemoryPoolMetricPersistenceWorker.WorkerRole.INSTANCE).tell(memoryPoolMetric.toData());
            } catch (WorkerInvokeException | WorkerNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
        });
    }

    private void sendToGCMetricPersistenceWorker(StreamModuleContext context, int applicationInstanceId,
        long timeBucket, List<GC> gcs) {
        gcs.forEach(gc -> {
            GCMetricDataDefine.GCMetric gcMetric = new GCMetricDataDefine.GCMetric();
            gcMetric.setId(timeBucket + Const.ID_SPLIT + applicationInstanceId + Const.ID_SPLIT + String.valueOf(gc.getPhraseValue()));
            gcMetric.setApplicationInstanceId(applicationInstanceId);
            gcMetric.setPhrase(gc.getPhraseValue());
            gcMetric.setCount(gc.getCount());
            gcMetric.setTime(gc.getTime());
            gcMetric.setTimeBucket(timeBucket);
            gcMetric.setS5TimeBucket(TimeBucketUtils.INSTANCE.getFiveSecondTimeBucket(timeBucket));
            try {
                logger.debug("send to gc metric persistence worker, id: {}", gcMetric.getId());
                context.getClusterWorkerContext().lookup(GCMetricPersistenceWorker.WorkerRole.INSTANCE).tell(gcMetric.toData());
            } catch (WorkerInvokeException | WorkerNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
        });
    }
}
