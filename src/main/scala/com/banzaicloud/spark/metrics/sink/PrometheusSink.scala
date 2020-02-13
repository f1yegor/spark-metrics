/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.banzaicloud.spark.metrics.sink

import java.io.File
import java.net.{InetAddress, URI, URL, UnknownHostException}
import java.util
import java.util.Properties
import java.util.concurrent.TimeUnit

import com.banzaicloud.spark.metrics.NameDecorator.Replace
import com.banzaicloud.spark.metrics.PushTimestampDecorator.PushTimestampProvider
import com.banzaicloud.spark.metrics.{DeduplicatedCollectorRegistry, SparkDropwizardExports, SparkJmxExports}
import com.codahale.metrics._
import io.prometheus.client.{Collector, CollectorRegistry}
import io.prometheus.client.dropwizard.DropwizardExports
import io.prometheus.client.exporter.PushGateway
import io.prometheus.jmx.JmxCollector
import org.apache.spark.internal.Logging
import com.banzaicloud.metrics.prometheus.client.exporter.PushGatewayWithTimestamp
import com.banzaicloud.metrics.prometheus.client.exporter.PushGatewayWithTimestamp
import com.banzaicloud.spark.metrics.DropwizardExportsWithMetricNameTransform
import com.codahale.metrics._
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.dropwizard.DropwizardExports
import org.apache.spark.internal.Logging
import io.prometheus.jmx.JmxCollector
import org.apache.spark.{SparkConf, SparkEnv}

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex
import PrometheusSink._

import scala.collection.immutable.ListMap

object PrometheusSink {

  trait SinkConfig extends Serializable {
    def metricsNamespace: Option[String]
    def sparkAppId: Option[String]
    def sparkAppName: Option[String]
    def executorId: Option[String]
  }

  val DEFAULT_PUSH_PERIOD: Int = 10
  val DEFAULT_PUSH_PERIOD_UNIT: TimeUnit = TimeUnit.SECONDS
  val DEFAULT_PUSHGATEWAY_ADDRESS: String = "127.0.0.1:9091"
  val DEFAULT_PUSHGATEWAY_ADDRESS_PROTOCOL: String = "http"
  val PUSHGATEWAY_ENABLE_TIMESTAMP: Boolean = false

  val KEY_PUSH_PERIOD = "period"
  val KEY_PUSH_PERIOD_UNIT = "unit"
  val KEY_PUSHGATEWAY_ADDRESS = "pushgateway-address"
  val KEY_PUSHGATEWAY_ADDRESS_PROTOCOL = "pushgateway-address-protocol"
  val KEY_PUSHGATEWAY_ENABLE_TIMESTAMP = "pushgateway-enable-timestamp"
  val DEFAULT_KEY_JMX_COLLECTOR_CONFIG = "/opt/spark/conf/jmx_collector.yaml"

  // metrics name replacement
  val KEY_METRICS_NAME_CAPTURE_REGEX = "metrics-name-capture-regex"
  val KEY_METRICS_NAME_REPLACEMENT = "metrics-name-replacement"
  val KEY_METRICS_NAME_TO_LOWERCASE = "metrics-name-to-lowercase"

  val SPARK_METRIC_LABELS_CONF = "spark.SPARK_METRIC_LABELS"

  val KEY_ENABLE_DROPWIZARD_COLLECTOR = "enable-dropwizard-collector"
  val KEY_ENABLE_JMX_COLLECTOR = "enable-jmx-collector"
  val KEY_ENABLE_HOSTNAME_IN_INSTANCE = "enable-hostname-in-instance"
  val KEY_JMX_COLLECTOR_CONFIG = "jmx-collector-config"

  // labels
  val KEY_LABELS = "labels"
  val KEY_GROUP_KEY = "group-key"

}

abstract class PrometheusSink(property: Properties,
                              registry: MetricRegistry,
                              sinkConfig: PrometheusSink.SinkConfig,
                              pushGatewayBuilder: URL => PushGateway
                             )  extends Logging {
  import sinkConfig._

  private val lbv = raw"(.+)\s*=\s*(.*)".r

  protected class Reporter(registry: MetricRegistry)
    extends ScheduledReporter(
      registry,
      "prometheus-reporter",
      MetricFilter.ALL,
      TimeUnit.SECONDS,
      TimeUnit.MILLISECONDS) {

    @throws(classOf[UnknownHostException])
    override def report(
                         gauges: util.SortedMap[String, Gauge[_]],
                         counters: util.SortedMap[String, Counter],
                         histograms: util.SortedMap[String, Histogram],
                         meters: util.SortedMap[String, Meter],
                         timers: util.SortedMap[String, Timer]): Unit = {

      logInfo(s"metricsNamespace=$metricsNamespace, sparkAppName=$sparkAppName, sparkAppId=$sparkAppId, " +
        s"executorId=$executorId")

      val labelsMap: Option[Map[String, String]] = collectLabels(sparkConf)
      logInfo(s"$KEY_LABELS -> ${labelsMap.getOrElse("")}")
      logInfo(s"$SPARK_METRIC_LABELS_CONF -> ${labelsMap.getOrElse("")}")

      val role: String = (sparkAppId, executorId) match {
        case (Some(_), Some("driver")) | (Some(_), Some("<driver>"))=> "driver"
        case (Some(_), Some(_)) => "executor"
        case _ => "unknown"
      }

      val job: String = role match {
        case "driver" => metricsNamespace.getOrElse(sparkAppId.get)
        case "executor" => metricsNamespace.getOrElse(sparkAppId.get)
        case _ => metricsNamespace.getOrElse("unknown")
      }

      val instance: String = if (enableHostNameInInstance) InetAddress.getLocalHost.getHostName else sparkAppId.getOrElse("")

      val appName: String = sparkAppName.getOrElse("")

      logInfo(s"role=$role, job=$job")

      val groupingKey = groupKeyMap.map { groupKey =>
        customGroupKey(role, executorId, groupKey)
      }.getOrElse(defaultGroupKey(role, executorId, appName, instance, labelsMap))

      pushGateway.pushAdd(pushRegistry, job, groupingKey.asJava)
    }
  }

  val pollPeriod: Int =
    Option(property.getProperty(KEY_PUSH_PERIOD))
      .map(_.toInt)
      .getOrElse(DEFAULT_PUSH_PERIOD)

  val pollUnit: TimeUnit =
    Option(property.getProperty(KEY_PUSH_PERIOD_UNIT))
      .map { s => TimeUnit.valueOf(s.toUpperCase) }
      .getOrElse(DEFAULT_PUSH_PERIOD_UNIT)

  val pushGatewayAddress =
    Option(property.getProperty(KEY_PUSHGATEWAY_ADDRESS))
      .getOrElse(DEFAULT_PUSHGATEWAY_ADDRESS)

  val pushGatewayAddressProtocol =
    Option(property.getProperty(KEY_PUSHGATEWAY_ADDRESS_PROTOCOL))
      .getOrElse(DEFAULT_PUSHGATEWAY_ADDRESS_PROTOCOL)

  val enableTimestamp: Boolean =
    Option(property.getProperty(KEY_PUSHGATEWAY_ENABLE_TIMESTAMP))
      .map(_.toBoolean)
      .getOrElse(PUSHGATEWAY_ENABLE_TIMESTAMP)

  val metricsNameCaptureRegex: Option[Regex] =
    Option(property.getProperty(KEY_METRICS_NAME_CAPTURE_REGEX))
      .map(new Regex(_))

  val metricsNameReplacement: String =
    Option(property.getProperty(KEY_METRICS_NAME_REPLACEMENT))
        .getOrElse("")

  val toLowerCase: Boolean =
    Option(property.getProperty(KEY_METRICS_NAME_TO_LOWERCASE))
      .map(_.toBoolean)
      .getOrElse(false)

  // validate pushgateway host:port
  Try(new URI(s"$pushGatewayAddressProtocol://$pushGatewayAddress")).get

  // validate metrics name capture regex
  if (metricsNameCaptureRegex.isDefined && metricsNameReplacement == "") {
    throw new IllegalArgumentException("Metrics name replacement must be specified if metrics name capture regexp is set !")
  }

  val labelsMap = parseLabels(KEY_LABELS).getOrElse(Map.empty[String, String])
  val groupKeyMap = parseLabels(KEY_GROUP_KEY)

  val enableDropwizardCollector: Boolean =
    Option(property.getProperty(KEY_ENABLE_DROPWIZARD_COLLECTOR))
      .map(_.toBoolean)
      .getOrElse(true)
  val enableJmxCollector: Boolean =
    Option(property.getProperty(KEY_ENABLE_JMX_COLLECTOR))
      .map(_.toBoolean)
      .getOrElse(false)
  val enableHostNameInInstance: Boolean =
    Option(property.getProperty(KEY_ENABLE_HOSTNAME_IN_INSTANCE))
      .map(_.toBoolean)
      .getOrElse(false)
  val jmxCollectorConfig =
    Option(property.getProperty(KEY_JMX_COLLECTOR_CONFIG))
      .getOrElse(DEFAULT_KEY_JMX_COLLECTOR_CONFIG)

  checkMinimalPollingPeriod(pollUnit, pollPeriod)

  logInfo("Initializing Prometheus Sink...")
  logInfo(s"Metrics polling period -> $pollPeriod $pollUnit")
  logInfo(s"Metrics timestamp enabled -> $enableTimestamp")
  logInfo(s"$KEY_PUSHGATEWAY_ADDRESS -> $pushGatewayAddress")
  logInfo(s"$KEY_PUSHGATEWAY_ADDRESS_PROTOCOL -> $pushGatewayAddressProtocol")
  logInfo(s"$KEY_METRICS_NAME_CAPTURE_REGEX -> ${metricsNameCaptureRegex.getOrElse("")}")
  logInfo(s"$KEY_METRICS_NAME_REPLACEMENT -> $metricsNameReplacement")
  logInfo(s"$KEY_METRICS_NAME_TO_LOWERCASE -> $toLowerCase")
  logInfo(s"$KEY_LABELS -> ${labelsMap}")
  logInfo(s"$KEY_JMX_COLLECTOR_CONFIG -> $jmxCollectorConfig")

  val pushRegistry: CollectorRegistry = new DeduplicatedCollectorRegistry()

  private val pushTimestamp = if (enableTimestamp) Some(PushTimestampProvider()) else None

  //  lazy val sparkMetricExports: DropwizardExports =
  //    metricsNameCaptureRegex match {
  //      case Some(r) =>
  //        new DropwizardExportsWithMetricNameTransform(registry,
  //                                                     r,
  //                                                     metricsNameReplacement,
  //                                                     toLowerCase)
  //      case _ => new com.banzaicloud.spark.metrics.DropwizardExports(registry)
  //    }

  private val replace = metricsNameCaptureRegex.map(Replace(_, metricsNameReplacement))

  lazy val sparkMetricExports = new SparkDropwizardExports(registry, replace, labelsMap, pushTimestamp)

  lazy val jmxMetrics: JmxCollector = new JmxCollector(new File(jmxCollectorConfig))

  lazy val jmxMetrics = new SparkJmxExports(new JmxCollector(new File(jmxCollectorConfig)), labelsMap, pushTimestamp)

  val pushGateway: PushGateway = pushGatewayBuilder(new URL(s"$pushGatewayAddressProtocol://$pushGatewayAddress"))

  val reporter = new Reporter(registry)

  def start(): Unit = {
    if (enableDropwizardCollector) {
      sparkMetricExports.register(pushRegistry)
    }
    if (enableJmxCollector) {
      jmxMetrics.register(pushRegistry)
    }
    reporter.start(pollPeriod, pollUnit)
  }

  def stop(): Unit = {
    reporter.stop()
    if (enableDropwizardCollector) {
      pushRegistry.unregister(sparkMetricExports)
    }
    if (enableJmxCollector) {
      pushRegistry.unregister(jmxMetrics)
    }
  }

  def report(): Unit = {
    reporter.report()
  }

  private def checkMinimalPollingPeriod(pollUnit: TimeUnit, pollPeriod: Int) {
    val period = TimeUnit.SECONDS.convert(pollPeriod, pollUnit)
    if (period < 1) {
      throw new IllegalArgumentException(
        s"Polling period $pollPeriod $pollUnit below than minimal polling period ")
    }
  }

  private def parseLabel(label: String): (String, String) = {
    label match {
      case lbv(label, value) => (Collector.sanitizeMetricName(label), value)
      case _ =>
        throw new IllegalArgumentException(
          "Can not parse labels ! Labels should be in label=value separated by commas format.")
    }
  }


//  private def parseLabels(labels: String): Try[Map[String, String]] = {
//    val kvs = labels.split(',')
//    val parsedLabels = Try(kvs.map(parseLabel).toMap)
//
//    parsedLabels
//  }

  private def parseLabels(key: String): Option[Map[String, String]] = {
    Option(property.getProperty(key)).map { labelsString =>
      val kvs = labelsString.split(',')
      val parsedLabels = kvs.map(parseLabel)
      ListMap(parsedLabels: _*) // List map to preserve labels order which might be important for group key
    }
  }

  /**
   * Default group key use instance name. So for every spark job instance it will create new metric group in the Push Gateway.
   * This may lead to OOM errors.
   * This method exists for backward compatibility.
   */
  private def defaultGroupKey(role: String,
                              executorId: Option[String],
                              appName: String,
                              instance: String,
                              labels: Map[String, String]) = {
    (role, executorId) match {
      case ("driver", _) =>
        ListMap("role" -> role, "app_name" -> appName, "instance" -> instance) ++ labels

      case ("executor", Some(id)) =>
        ListMap("role" -> role, "app_name" -> appName, "instance" -> instance, "number" -> id) ++ labels
      case _ =>
        ListMap("role" -> role)
    }
  }

  private def customGroupKey(role: String,
                             executorId: Option[String],
                             labels: Map[String, String]) = {
    (role, executorId) match {
      case ("driver", _) =>
        ListMap("role" -> role) ++ labels

      case ("executor", Some(id)) =>
        ListMap("role" -> role, "number" -> id) ++ labels
      case _ =>
        ListMap("role" -> role)
    }
  }


  // an attempt to pass labels via env vars
  private def collectEnvLabels(sparkConf: SparkConf): Map[String, String] = {
    val kvs = sparkConf.get(SPARK_METRIC_LABELS_CONF, "")
    if (kvs.isEmpty) {
      return Map.empty
    }
    kvs.split(",").map(parseLabel).toMap
  }

  /**
    * 2 sources for labels:
    * 1. from config
    * @return
    */
  private def collectLabels(
      sparkConf: SparkConf): Option[Map[String, String]] = {
    // parse labels
    val configLabels: Option[Try[Map[String, String]]] =
      Option(property.getProperty(KEY_LABELS))
        .map(parseLabels)

    val configLabelsMap = configLabels match {
      case Some(Success(lm))  => lm
      case Some(Failure(err)) => throw err
      case _                  => Map.empty
    }

    val envLabels = collectEnvLabels(sparkConf)
    Option(configLabelsMap ++ envLabels)
  }

  private def customGroupKey(role: String,
                             executorId: Option[String],
                             labels: Map[String, String]): ListMap[String, String] = {
    (role, executorId) match {
      case ("driver", _) =>
        ListMap("role" -> role) ++ labels

      case ("executor", Some(id)) =>
        ListMap("role" -> role, "number" -> id) ++ labels
      case _ =>
        ListMap("role" -> role)
    }
  }
}
