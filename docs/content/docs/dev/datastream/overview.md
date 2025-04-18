---
title: Overview
weight: 1
type: docs
aliases:
  - /dev/datastream_api.html
  - /apis/common/index.html
  - /dev/datastream_api
  - /apis/streaming/index.html
  - /apis/streaming_guide.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Flink DataStream API Programming Guide

DataStream programs in Flink are regular programs that implement transformations on data streams
(e.g., filtering, updating state, defining windows, aggregating). The data streams are initially created from various
sources (e.g., message queues, socket streams, files). Results are returned via sinks, which may for
example write the data to files, or to standard output (for example the command line
terminal). Flink programs run in a variety of contexts, standalone, or embedded in other programs.
The execution can happen in a local JVM, or on clusters of many machines.

In order to create your own Flink DataStream program, we encourage you to start
with [anatomy of a Flink Program](#anatomy-of-a-flink-program) and gradually
add your own [stream transformations]({{< ref "docs/dev/datastream/operators/overview" >}}). The remaining sections act as references for additional operations and advanced features.

What is a DataStream?
----------------------

The DataStream API gets its name from the special `DataStream` class that is
used to represent a collection of data in a Flink program. You can think of
them as immutable collections of data that can contain duplicates. This data
can either be finite or unbounded, the API that you use to work on them is the
same.

A `DataStream` is similar to a regular Java `Collection` in terms of usage but
is quite different in some key ways. They are immutable, meaning that once they
are created you cannot add or remove elements. You can also not simply inspect
the elements inside but only work on them using the `DataStream` API
operations, which are also called transformations.

You can create an initial `DataStream` by adding a source in a Flink program.
Then you can derive new streams from this and combine them by using API methods
such as `map`, `filter`, and so on.

Anatomy of a Flink Program
--------------------------

Flink programs look like regular programs that transform `DataStreams`.  Each
program consists of the same basic parts:

1. Obtain an `execution environment`,
2. Load/create the initial data,
3. Specify transformations on this data,
4. Specify where to put the results of your computations,
5. Trigger the program execution

{{< tabs "fa68701c-59e8-4509-858e-3e8a123eeacf" >}}
{{< tab "Java" >}}

We will now give an overview of each of those steps, please refer to the
respective sections for more details. Note that all core classes of the Java
DataStream API can be found in {{< gh_link
file="/flink-streaming-java/src/main/java/org/apache/flink/streaming/api"
name="org.apache.flink.streaming.api" >}}.

The `StreamExecutionEnvironment` is the basis for all Flink programs. You can
obtain one using these static methods on `StreamExecutionEnvironment`:

```java
getExecutionEnvironment();

createLocalEnvironment();

createRemoteEnvironment(String host, int port, String... jarFiles);
```

Typically, you only need to use `getExecutionEnvironment()`, since this will do
the right thing depending on the context: if you are executing your program
inside an IDE or as a regular Java program it will create a local environment
that will execute your program on your local machine. If you created a JAR file
from your program, and invoke it through the [command line]({{< ref "docs/deployment/cli" >}}), the Flink cluster manager will execute your main method and
`getExecutionEnvironment()` will return an execution environment for executing
your program on a cluster.

For specifying data sources the execution environment has several methods to
read from files using various methods: you can just read them line by line, as
CSV files, or using any of the other provided sources. To just read a text file
as a sequence of lines, you can use:

```java
final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

FileSource<String> fileSource = FileSource.forRecordStreamFormat(
        new TextLineInputFormat(), new Path("file:///path/to/file")
    ).build();
DataStream<String> text = env.fromSource(
    fileSource,
    WatermarkStrategy.noWatermarks(),
    "file-input"
);
```

This will give you a DataStream on which you can then apply transformations to create new
derived DataStreams.

You apply transformations by calling methods on DataStream with a
transformation functions. For example, a map transformation looks like this:

```java
DataStream<String> input = ...;

DataStream<Integer> parsed = input.map(new MapFunction<String, Integer>() {
    @Override
    public Integer map(String value) {
        return Integer.parseInt(value);
    }
});
```

This will create a new DataStream by converting every String in the original
collection to an Integer.

Once you have a DataStream containing your final results, you can write it to
an outside system by creating a sink. These are just some example methods for
creating a sink:

```java
stream.sinkTo(
    FileSink.forRowFormat(
        new Path("outputPath"), 
        new SimpleStringEncoder<>()
    ).build()
);

stream.print();
```

{{< /tab >}}
{{< /tabs >}}

Once you specified the complete program you need to **trigger the program
execution** by calling `execute()` on the `StreamExecutionEnvironment`.
Depending on the type of the `ExecutionEnvironment` the execution will be
triggered on your local machine or submit your program for execution on a
cluster.

The `execute()` method will wait for the job to finish and then return a
`JobExecutionResult`, this contains execution times and accumulator results.

If you don't want to wait for the job to finish, you can trigger asynchronous
job execution by calling `executeAsync()` on the `StreamExecutionEnvironment`.
It will return a `JobClient` with which you can communicate with the job you
just submitted. For instance, here is how to implement the semantics of
`execute()` by using `executeAsync()`.

```java
final JobClient jobClient = env.executeAsync();

final JobExecutionResult jobExecutionResult = jobClient.getJobExecutionResult().get();
```

That last part about program execution is crucial to understanding when and how
Flink operations are executed. All Flink programs are executed lazily: When the
program's main method is executed, the data loading and transformations do not
happen directly. Rather, each operation is created and added to a dataflow
graph. The operations are actually executed when the execution is explicitly
triggered by an `execute()` call on the execution environment.  Whether the
program is executed locally or on a cluster depends on the type of execution
environment.

The lazy evaluation lets you construct sophisticated programs that Flink
executes as one holistically planned unit.

{{< top >}}

Example Program
---------------

The following program is a complete, working example of streaming window word count application, that counts the
words coming from a web socket in 5 second windows. You can copy &amp; paste the code to run it locally.

{{< tabs "7ef5e21b-c24f-404f-af39-e21231b15e0d" >}}
{{< tab "Java" >}}

```java
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import java.time.Duration;
import org.apache.flink.util.Collector;

public class WindowWordCount {

    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Tuple2<String, Integer>> dataStream = env
                .socketTextStream("localhost", 9999)
                .flatMap(new Splitter())
                .keyBy(value -> value.f0)
                .window(TumblingProcessingTimeWindows.of(Duration.ofSeconds(5)))
                .sum(1);

        dataStream.print();

        env.execute("Window WordCount");
    }

    public static class Splitter implements FlatMapFunction<String, Tuple2<String, Integer>> {
        @Override
        public void flatMap(String sentence, Collector<Tuple2<String, Integer>> out) throws Exception {
            for (String word: sentence.split(" ")) {
                out.collect(new Tuple2<String, Integer>(word, 1));
            }
        }
    }

}
```

{{< /tab >}}
{{< /tabs >}}

To run the example program, start the input stream with netcat first from a terminal:

```bash
nc -lk 9999
```

Just type some words hitting return for a new word. These will be the input to the
word count program. If you want to see counts greater than 1, type the same word again and again within
5 seconds (increase the window size from 5 seconds if you cannot type that fast &#9786;).

{{< top >}}

Data Sources
------------

{{< tabs "8104e62c-db79-40b0-8519-0063e9be791f" >}}
{{< tab "Java" >}}

Sources are where your program reads its input from. You can attach a source to your program by
using `StreamExecutionEnvironment.addSource(sourceFunction)`. Flink comes with a number of pre-implemented
source functions, but you can always write your own custom sources by implementing the `SourceFunction`
for non-parallel sources, or by implementing the `ParallelSourceFunction` interface or extending the
`RichParallelSourceFunction` for parallel sources.

There are several predefined stream sources accessible from the `StreamExecutionEnvironment`:

File-based:

- `fromSource(FileSource.forRecordStreamFormat(format, paths).build())` - Read record-by-record from files.

- `readFile(fileInputFormat, path)` - Reads (once) files as dictated by the specified file input format.

- `readFile(fileInputFormat, path, watchType, interval, pathFilter, typeInfo)` -  This is the method called internally by the two previous ones. It reads files in the `path` based on the given `fileInputFormat`. Depending on the provided `watchType`, this source may periodically monitor (every `interval` ms) the path for new data (`FileProcessingMode.PROCESS_CONTINUOUSLY`), or process once the data currently in the path and exit (`FileProcessingMode.PROCESS_ONCE`). Using the `pathFilter`, the user can further exclude files from being processed.

    *IMPLEMENTATION:*

    Under the hood, Flink splits the file reading process into two sub-tasks, namely *directory monitoring* and *data reading*. Each of these sub-tasks is implemented by a separate entity. Monitoring is implemented by a single, **non-parallel** (parallelism = 1) task, while reading is performed by multiple tasks running in parallel. The parallelism of the latter is equal to the job parallelism. The role of the single monitoring task is to scan the directory (periodically or only once depending on the `watchType`), find the files to be processed, divide them in *splits*, and assign these splits to the downstream readers. The readers are the ones who will read the actual data. Each split is read by only one reader, while a reader can read multiple splits, one-by-one.

    *IMPORTANT NOTES:*

    1. If the `watchType` is set to `FileProcessingMode.PROCESS_CONTINUOUSLY`, when a file is modified, its contents are re-processed entirely. This can break the "exactly-once" semantics, as appending data at the end of a file will lead to **all** its contents being re-processed.

    2. If the `watchType` is set to `FileProcessingMode.PROCESS_ONCE`, the source scans the path **once** and exits, without waiting for the readers to finish reading the file contents. Of course the readers will continue reading until all file contents are read. Closing the source leads to no more checkpoints after that point. This may lead to slower recovery after a node failure, as the job will resume reading from the last checkpoint.

Socket-based:

- `socketTextStream` - Reads from a socket. Elements can be separated by a delimiter.

Collection-based:

- `fromData(Collection)` - Creates a data stream from the Java Java.util.Collection. All elements
  in the collection must be of the same type.

- `fromData(T ...)` - Creates a data stream from the given sequence of objects. All objects must be
  of the same type.

- `fromParallelCollection(SplittableIterator, Class)` - Creates a data stream from an iterator, in
  parallel. The class specifies the data type of the elements returned by the iterator.

- `fromSequence(from, to)` - Generates the sequence of numbers in the given interval, in
  parallel.

Custom:

- `addSource` - Attach a new source function. For example, to read from Apache Kafka you can use
    `addSource(new FlinkKafkaConsumer<>(...))`. See [connectors]({{< ref "docs/connectors/datastream/overview" >}}) for more details.

{{< /tab >}}
{{< /tabs >}}

{{< top >}}

DataStream Transformations
--------------------------

Please see [operators]({{< ref "docs/dev/datastream/operators/overview" >}}) for an overview of the available stream transformations.

{{< top >}}

Data Sinks
----------

{{< tabs "355a7803-ea54-44b2-9970-e0cdd58a959b" >}}
{{< tab "Java" >}}

Data sinks consume DataStreams and forward them to files, sockets, external systems, or print them.
Flink comes with a variety of built-in output formats that are encapsulated behind operations on the
DataStreams:

- `sinkTo(FileSink.forRowFormat(new Path("outputPath"), new SimpleStringEncoder<>()).build())` - Writes elements line-wise as Strings. The Strings are
  obtained by calling the *toString()* method of each element.

- `print()` / `printToErr()`  - Prints the *toString()* value
of each element on the standard out / standard error stream. Optionally, a prefix (msg) can be provided which is
prepended to the output. This can help to distinguish between different calls to *print*. If the parallelism is
greater than 1, the output will also be prepended with the identifier of the task which produced the output.

- `writeUsingOutputFormat()` / `FileOutputFormat` - Method and base class for custom file outputs. Supports
  custom object-to-bytes conversion.

- `writeToSocket` - Writes elements to a socket according to a `SerializationSchema`

- `addSink` - Invokes a custom sink function. Flink comes bundled with connectors to other systems (such as
    Apache Kafka) that are implemented as sink functions.

{{< /tab >}}
{{< /tabs >}}

Note that the `write*()` methods on `DataStream` are mainly intended for debugging purposes.
They are not participating in Flink's checkpointing, this means these functions usually have
at-least-once semantics. The data flushing to the target system depends on the implementation of the
OutputFormat. This means that not all elements send to the OutputFormat are immediately showing up
in the target system. Also, in failure cases, those records might be lost.

For reliable, exactly-once delivery of a stream into a file system, use the `FileSink`.
Also, custom implementations through the `.addSink(...)` method can participate in Flink's checkpointing
for exactly-once semantics.

{{< top >}}

Execution Parameters
--------------------

The `StreamExecutionEnvironment` contains the `ExecutionConfig` which allows to set job specific configuration values for the runtime.

Please refer to [execution configuration]({{< ref "docs/deployment/config" >}})
for an explanation of most parameters. These parameters pertain specifically to the DataStream API:

- `setAutoWatermarkInterval(long milliseconds)`: Set the interval for automatic watermark emission. You can
    get the current value with `long getAutoWatermarkInterval()`

{{< top >}}

### Fault Tolerance

[State & Checkpointing]({{< ref "docs/dev/datastream/fault-tolerance/checkpointing" >}}) describes how to enable and configure Flink's checkpointing mechanism.

### Controlling Latency

By default, elements are not transferred on the network one-by-one (which would cause unnecessary network traffic)
but are buffered. The size of the buffers (which are actually transferred between machines) can be set in the Flink config files.
While this method is good for optimizing throughput, it can cause latency issues when the incoming stream is not fast enough.
To control throughput and latency, you can use `env.setBufferTimeout(timeoutMillis)` on the execution environment
(or on individual operators) to set a maximum wait time for the buffers to fill up. After this time, the
buffers are sent automatically even if they are not full. The default value for this timeout is 100 ms.

Usage:

{{< tabs "6988880d-fb9f-4f2e-93b6-54cb85fe374c" >}}
{{< tab "Java" >}}
```java
LocalStreamEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
env.setBufferTimeout(timeoutMillis);

env.generateSequence(1,10).map(new MyMapper()).setBufferTimeout(timeoutMillis);
```
{{< /tab >}}
{{< /tabs >}}

To maximize throughput, set `setBufferTimeout(-1)` which will remove the timeout and buffers will only be
flushed when they are full. To minimize latency, set the timeout to a value close to 0 (for example 5 or 10 ms).
A buffer timeout of 0 should be avoided, because it can cause severe performance degradation.

{{< top >}}

Debugging
---------

Before running a streaming program in a distributed cluster, it is a good
idea to make sure that the implemented algorithm works as desired. Hence, implementing data analysis
programs is usually an incremental process of checking results, debugging, and improving.

Flink provides features to significantly ease the development process of data analysis
programs by supporting local debugging from within an IDE, injection of test data, and collection of
result data. This section give some hints how to ease the development of Flink programs.

### Local Execution Environment

A `LocalStreamEnvironment` starts a Flink system within the same JVM process it was created in. If you
start the LocalEnvironment from an IDE, you can set breakpoints in your code and easily debug your
program.

A LocalEnvironment is created and used as follows:

{{< tabs "d4afc70f-dce0-43af-8a81-6714fecb34b2" >}}
{{< tab "Java" >}}
```java
final StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();

DataStream<String> lines = env.addSource(/* some source */);
// build your program

env.execute();
```
{{< /tab >}}
{{< /tabs >}}

### Collection Data Sources

Flink provides special data sources which are backed
by Java collections to ease testing. Once a program has been tested, the sources and sinks can be
easily replaced by sources and sinks that read from / write to external systems.

Collection data sources can be used as follows:

{{< tabs "d2a2ad42-e763-42bb-abbe-f812adf28953" >}}
{{< tab "Java" >}}
```java
final StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();

// Create a DataStream from a list of elements
DataStream<Integer> myInts = env.fromElements(1, 2, 3, 4, 5);

// Create a DataStream from any Java collection
List<Tuple2<String, Integer>> data = ...
DataStream<Tuple2<String, Integer>> myTuples = env.fromCollection(data);

// Create a DataStream from an Iterator
Iterator<Long> longIt = ...;
DataStream<Long> myLongs = env.fromCollection(longIt, Long.class);
```
{{< /tab >}}
{{< /tabs >}}

**Note:** Currently, the collection data source requires that data types and iterators implement
`Serializable`. Furthermore, collection data sources can not be executed in parallel (
parallelism = 1).

### Iterator Data Sink

Flink also provides a sink to collect DataStream results for testing and debugging purposes. It can be used as follows:

{{< tabs "125e228e-13b5-4c77-93a7-c0f436fcdd2f" >}}
{{< tab "Java" >}}
```java
DataStream<Tuple2<String, Integer>> myResult = ...;
Iterator<Tuple2<String, Integer>> myOutput = myResult.collectAsync();
```

{{< /tab >}}
{{< /tabs >}}

Where to go next?
-----------------

* [Operators]({{< ref "docs/dev/datastream/operators/overview" >}}): Specification of available streaming operators.
* [Event Time]({{< ref "docs/concepts/time" >}}): Introduction to Flink's notion of time.
* [State & Fault Tolerance]({{< ref "docs/dev/datastream/fault-tolerance/state" >}}): Explanation of how to develop stateful applications.
* [Connectors]({{< ref "docs/connectors/datastream/overview" >}}): Description of available input and output connectors.

{{< top >}}
