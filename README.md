# Schema
Database transactions are fundamentally lacking for the following three reasons. This library addresses (1) by providing a new language for specifying transactions on arbitrary datastores, (2) by permitting non-linear control flow and arbitrary computation within transactions, and (3) by utilizing optimistic concurrency controls to guarantee transactional consistency without sacrificing performance.

1. __Disparate Interfaces__: Every database has a different language for specifying transactions. Thus, programmers require an upfront knowledge of the choice of underlying database when writing transactional queries. Whenever a decision is made to change the database, all existing transactions must be rewritten to conform to the new specification. Indeed, the tremendous expense of rewriting transactions is a significant reason why large companies are locked into their existing database infrastructure and are unable to modernize them.

2. __Deficient Functionality__: In particular, there are two key areas where databases are typically lacking in functionality. First, databases generally do not support cross-shard transactions. Thus, the design of an application server requires upfront knowledge of data placement. This limits the kinds of transactions that may be performed, since transactions must operate on data within a single shard. Second, databases generally only support linear control flow and simple operations within transactions. As a result, transactions are limited in scope and utility.

3. __Performance Penalties__: Most databases that support transactions do so at a significant cost. For example, Redis guarantees that [“All the commands in a transaction are serialized and executed sequentially. It can never happen that a request issued by another client is served in the middle of the execution of a Redis transaction. This guarantees that the commands are executed as a single isolated operation.”](https://redis.io/topics/transactions) This means that transactions in Redis may only be performed one-at-a-time, so programmers may be forced to sacrifice write performance for transactional safety.
