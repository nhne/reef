/**
 * Copyright (C) 2014 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoft.reef.examples.data.loading;

import com.microsoft.reef.annotations.audience.DriverSide;
import com.microsoft.reef.driver.context.ActiveContext;
import com.microsoft.reef.driver.context.ContextConfiguration;
import com.microsoft.reef.driver.task.CompletedTask;
import com.microsoft.reef.driver.task.TaskConfiguration;
import com.microsoft.reef.io.data.loading.api.DataLoadingService;
import com.microsoft.reef.poison.context.PoisonedContextConfiguration;
import com.microsoft.tang.Configuration;
import com.microsoft.tang.Tang;
import com.microsoft.tang.annotations.Unit;
import com.microsoft.tang.exceptions.BindException;
import com.microsoft.wake.EventHandler;

import javax.inject.Inject;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Driver side for the line counting
 * demo that uses the data loading service
 */
@DriverSide
@Unit
public class LineCounter {

  private static final Logger LOG = Logger.getLogger(LineCounter.class.getName());

  private final DataLoadingService dataLoadingService;

  private final AtomicInteger ctrlCtxIds = new AtomicInteger();

  private final AtomicInteger completedDataTasks;

  private final AtomicInteger lineCnt = new AtomicInteger();

  @Inject
  public LineCounter(final DataLoadingService dataLoadingService) {
    this.dataLoadingService = dataLoadingService;
    this.completedDataTasks = new AtomicInteger(dataLoadingService.getNumberOfPartitions());
  }

  public class TaskCompletedHandler implements EventHandler<CompletedTask> {
    @Override
    public void onNext(final CompletedTask completedTask) {
      final String taskId = completedTask.getId();
      LOG.log(Level.INFO, "Completed Task: {0}", taskId);
      final byte[] retVal = completedTask.get();
      final String retStr = retVal == null ? "No RetVal": new String(retVal);
      LOG.log(Level.INFO, "Line count from {0} : {1}", new String[] { taskId, retStr });
      lineCnt.addAndGet(Integer.parseInt(retStr));
      if (completedDataTasks.decrementAndGet() == 0) {
        LOG.log(Level.INFO, "Total line count: {0}", lineCnt.get());
      }
      LOG.log(Level.INFO, "Releasing Context: {0}", taskId);
      completedTask.getActiveContext().close();
    }
  }

  public class ContextActiveHandler implements EventHandler<ActiveContext> {

    @Override
    public void onNext(final ActiveContext activeContext) {
      if (dataLoadingService.isDataLoadedContext(activeContext)) {
        final Configuration poissonConfiguration = PoisonedContextConfiguration.CONF
            .set(PoisonedContextConfiguration.CRASH_PROBABILITY, "0.4")
            .set(PoisonedContextConfiguration.CRASH_TIMEOUT, "1")
            .build();

        activeContext.submitContext(Tang.Factory.getTang()
            .newConfigurationBuilder(
                poissonConfiguration,
                ContextConfiguration.CONF.set(ContextConfiguration.IDENTIFIER, "LineCountCtxt-" + ctrlCtxIds.getAndIncrement()).build()
            )
            .build());
      } else if (activeContext.getId().startsWith("LineCountCtxt")) {
        final String evaluatorId = activeContext.getEvaluatorId();
        final String taskId = "LineCountTask-" + ctrlCtxIds.getAndIncrement();
        try {

          final Configuration taskConfiguration = TaskConfiguration.CONF
              .set(TaskConfiguration.IDENTIFIER, taskId)
              .set(TaskConfiguration.TASK, LineCountingTask.class)
              .build();

          activeContext.submitTask(taskConfiguration);
        } catch (final BindException e) {
          throw new RuntimeException("Unable to create context/task configuration for " + evaluatorId, e);
        }
      } else {
        LOG.log(Level.INFO, "Line count Compute Task " + activeContext.getId() +
            " -- Closing");
        activeContext.close();
        return;
      }
    }
  }
}
