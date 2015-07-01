package org.embulk.input

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{AWSCredentials, BasicAWSCredentials, AWSCredentialsProvider}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.model._
import org.embulk.spi._

import java.util.{ArrayList => JArrayList, List => JList, Map => JMap}
import scala.collection.JavaConversions._

object DynamoDBUtil {
  private def getCredentialsProvider(task: PluginTask): AWSCredentialsProvider = {
   {for {
      accessKey <- Option(task.getAccessKey.orNull)
      secretKey <- Option(task.getSecretKey.orNull)
    } yield {
     new AWSCredentialsProvider {
       override def refresh(): Unit = { }
       override def getCredentials: AWSCredentials = {
         new BasicAWSCredentials(accessKey, secretKey)
       }
     }
    }}.getOrElse{
      new ProfileCredentialsProvider()
    }
  }

  def createClient(task: PluginTask): AmazonDynamoDBClient = {
    val client: AmazonDynamoDBClient = new AmazonDynamoDBClient(
      new ClientConfiguration().withMaxConnections(10))
      .withRegion(Regions.fromName(task.getRegion))

    client
  }


  def scan(client: AmazonDynamoDBClient, task: PluginTask, schema: Schema, output: PageOutput): Unit = {
    val allocator: BufferAllocator = task.getBufferAllocator
    val pageBuilder: PageBuilder = new PageBuilder(allocator, schema, output)

    val attributes: JList[String] = new JArrayList[String]()

    schema.getColumns.foreach { column =>
      attributes.add(column.getName)
    }
    val scanFilter: Map[String, Condition] = createScanFilter(task)
    var evaluateKey: JMap[String, AttributeValue] = null

    val scanLimit: Long   = task.getScanLimit
    val recordLimit: Long = task.getRecordLimit
    var recordCount: Long = 0

    do {
      val batchSize = getScanLimit(scanLimit, recordLimit, recordCount)

      val request: ScanRequest = new ScanRequest()
        .withTableName(task.getTable)
        .withAttributesToGet(attributes)
        .withScanFilter(scanFilter)
        .withExclusiveStartKey(evaluateKey)

      if (batchSize > 0) {
        request.setLimit(batchSize)
      }

      val result: ScanResult = client.scan(request)
      evaluateKey = result.getLastEvaluatedKey

      result.getItems.foreach { item =>
        schema.getColumns.foreach { column =>
          val value = item.get(column.getName)
          column.getType.getName match {
            case "string" =>
              pageBuilder.setString(column, Option(value) map { _.getS } getOrElse { "" })
            case "long" =>
              pageBuilder.setLong(column, Option(value) map { _.getN.toLong } getOrElse { 0L })
            case "double" =>
              pageBuilder.setDouble(column, Option(value) map { _.getN.toDouble } getOrElse { 0D })
            case "boolean" =>
              pageBuilder.setBoolean(column, Option(value) map { _.getBOOL == true } getOrElse { false })
            case _ => /* Do nothing */
          }
        }
        pageBuilder.addRecord()
        recordCount += 1
      }
    } while(evaluateKey != null && (recordLimit == 0 || recordLimit > recordCount))

    pageBuilder.finish()
  }

  private def getScanLimit(scanLimit: Long, recordLimit: Long, recordCount: Long): Int = {
    if (scanLimit > 0 && recordLimit > 0) {
      math.min(scanLimit, recordLimit - recordCount).toInt
    } else if (scanLimit > 0 || recordLimit > 0) {
      math.max(scanLimit, recordLimit).toInt
    } else { 0 }
  }

  private def createScanFilter(task: PluginTask): Map[String, Condition] = {
    val filterMap = collection.mutable.HashMap[String, Condition]()

    Option(task.getFilters.orNull).map { filters =>
      filters.getFilters.map { filter =>
        val attributeValueList = collection.mutable.ArrayBuffer[AttributeValue]()
        attributeValueList += createAttributeValue(filter.getType, filter.getValue)
        Option(filter.getValue2).map { value2 => attributeValueList += createAttributeValue(filter.getType, value2) }

        filterMap += filter.getName -> new Condition()
          .withComparisonOperator(filter.getCondition)
          .withAttributeValueList(attributeValueList)
      }
    }

    filterMap.toMap
  }

  private def createAttributeValue(t: String, v: String): AttributeValue = {
    t match {
      case "string" =>
        new AttributeValue().withS(v)
      case "long" | "double" =>
        new AttributeValue().withN(v)
      case "boolean" =>
        new AttributeValue().withBOOL(v.toBoolean)
    }
  }
}
