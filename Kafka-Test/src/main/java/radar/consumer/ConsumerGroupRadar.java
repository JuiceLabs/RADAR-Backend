package radar.consumer;

import org.apache.kafka.common.errors.IllegalGenerationException;
import org.apache.log4j.Logger;

import java.security.InvalidParameterException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import radar.utils.RadarThreadFactoryBuilder;

/**
 * Created by Francesco Nobilia on 06/10/2016.
 */
public abstract class ConsumerGroupRadar implements Runnable{

    private final static Logger log = Logger.getLogger(ConsumerGroupRadar.class);

    private boolean workersCreated = false;

    private final Integer numThreads;
    private ExecutorService executor;
    private List<ConsumerRadar> workers;

    private String poolName;

    public ConsumerGroupRadar(int numThreads, String poolName) throws InvalidParameterException{
        this(numThreads);

        if((poolName != null) && (poolName.length() > 0)){
            this.poolName = poolName+"-Consumer";
        }
    }

    public ConsumerGroupRadar(int numThreads) throws InvalidParameterException{
        if(numThreads < 1){
            throw new InvalidParameterException("A group must contain at least 2 elements");
        }

        this.numThreads = numThreads;
        this.poolName = "GroupPool-Consumer";
    }

    public void initiWorkers(){
        workers = getWorkerList();
        workersCreated = true;
    }

    private List<ConsumerRadar> getWorkerList(){
        List<ConsumerRadar> list = new LinkedList<>();

        for(int i=0; i<numThreads; i++){
            list.add(i,createConsumer());
        }

        return list;
    }

    public abstract ConsumerRadar createConsumer();

    @Override
    public void run() {

        if(!workersCreated){
            throw new IllegalGenerationException("Before starting the group, initWorkers has to be" +
                    "invoked. For example at the end of the derived class's constructor!");
        }

        ThreadFactory threadFactory = new RadarThreadFactoryBuilder()
                .setNamePrefix(poolName)
                .setDaemon(false)
                .setPriority(Thread.NORM_PRIORITY)
                .build();

        executor = Executors.newFixedThreadPool(numThreads,threadFactory);
        workers.forEach(worker -> executor.submit(worker));
    }

    public void shutdown() throws InterruptedException {
        if(executor != null){
            executor.shutdown();
        }

        if(workers != null){
            for(ConsumerRadar consumer : workers){
                consumer.shutdown();
            }
        }

        try{
            if(!executor.awaitTermination(5000, TimeUnit.MILLISECONDS)){
                log.info("Timed out waiting for consumer threads to shut down, exiting uncleanly");
            }
        }catch(InterruptedException e){
            log.error("Interrupted during shutdown, exiting uncleanly");
        }
    }
}
