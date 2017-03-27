//--------------------------------------------------------------------------------------------------
// Copyright (c) YugaByte, Inc.
//
// This module is to define CQL processor. Each processor will be handling one and only one request
// at a time. As a result all processing code in this module and the modules that it is calling
// does not need to be thread safe.
//--------------------------------------------------------------------------------------------------

#ifndef YB_CQLSERVER_CQL_PROCESSOR_H_
#define YB_CQLSERVER_CQL_PROCESSOR_H_

#include "yb/client/client.h"
#include "yb/cqlserver/cql_message.h"
#include "yb/cqlserver/cql_service.h"
#include "yb/sql/sql_processor.h"
#include "yb/sql/statement.h"
#include "yb/rpc/inbound_call.h"

namespace yb {
namespace cqlserver {

class CQLServiceImpl;

class CQLMetrics : public sql::SqlMetrics {
 public:
  explicit CQLMetrics(const scoped_refptr<yb::MetricEntity>& metric_entity);

  scoped_refptr<yb::Histogram> time_to_process_request_;
  scoped_refptr<yb::Histogram> time_to_get_cql_processor_;
  scoped_refptr<yb::Histogram> time_to_parse_cql_wrapper_;
  scoped_refptr<yb::Histogram> time_to_execute_cql_request_;

  scoped_refptr<yb::Histogram> time_to_queue_cql_response_;
  scoped_refptr<yb::Counter> num_errors_parsing_cql_;
  // Rpc level metrics
  yb::rpc::RpcMethodMetrics rpc_method_metrics_;
};

// A CQL statement that is prepared and cached.
class CQLStatement : public sql::Statement {
 public:
  explicit CQLStatement(const std::string& keyspace, const std::string& sql_stmt);

  // Return the query id.
  CQLMessage::QueryId query_id() const { return GetQueryId(keyspace_, text_); }

  // Get/set position of the statement in the LRU.
  CQLStatementListPos pos() const { return pos_; }
  void set_pos(CQLStatementListPos pos) { pos_ = pos; }

  // Return the query id of a statement.
  static CQLMessage::QueryId GetQueryId(const std::string& keyspace, const std::string& sql_stmt);

 private:
  // Position of the statement in the LRU.
  CQLStatementListPos pos_;
};

class CQLProcessor : public sql::SqlProcessor {
 public:
  // Constructor and destructor.
  explicit CQLProcessor(CQLServiceImpl* service_impl);
  ~CQLProcessor();

  // Processing an inbound call.
  void ProcessCall(rpc::CQLInboundCall* cql_call);

  // Process a PREPARE request.
  CQLResponse *ProcessPrepare(const PrepareRequest& req);

  // Process a EXECUTE request.
  void ProcessExecute(const ExecuteRequest& req, Callback<void(CQLResponse*)> cb);

  // Process a QUERY request.
  void ProcessQuery(const QueryRequest& req, Callback<void(CQLResponse*)> cb);

 private:
  // Run in response to ProcessQuery, ProcessExecute and ProcessCall
  void ProcessQueryDone(
      const QueryRequest& req, Callback<void(CQLResponse*)> cb, const Status& s,
      sql::ExecutedResult::SharedPtr result);
  void ProcessExecuteDone(
      const ExecuteRequest& req, std::shared_ptr<CQLStatement> stmt,
      Callback<void(CQLResponse*)> cb, const Status& s, sql::ExecutedResult::SharedPtr result);
  void ProcessCallDone(
      rpc::CQLInboundCall* cql_call, const CQLRequest* request, const MonoTime& start,
      CQLResponse* response);

  CQLResponse* ReturnResponse(
      const CQLRequest& req, Status s, sql::ExecutedResult::SharedPtr result);
  void SendResponse(rpc::CQLInboundCall* cql_call, CQLResponse* response);

  // Pointer to the containing CQL service implementation.
  CQLServiceImpl* const service_impl_;

  // CQL metrics.
  std::shared_ptr<CQLMetrics> cql_metrics_;
};

}  // namespace cqlserver
}  // namespace yb

#endif  // YB_CQLSERVER_CQL_PROCESSOR_H_
