package com.bobo.reves.mysql;

import com.bobo.reves.mysql.annotation.MySQLColumnName;
import com.bobo.reves.mysql.annotation.MySQLExclude;
import com.bobo.reves.mysql.annotation.MySQLId;
import com.bobo.reves.mysql.annotation.MySQLTableName;
import com.bobo.reves.mysql.utils.MyCompositeFuture;
import com.bobo.reves.mysql.utils.StringUtils;
import com.google.common.base.CaseFormat;
import com.google.common.reflect.TypeToken;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.PoolOptions;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * mysql 基础类
 *
 * @author BO
 * @date 2020-12-26 14:32
 * @since 2020/12/26
 **/
public abstract class BaseMysql<T> extends MysqlWrapper {

	public BaseMysql(Vertx vertx, MySQLConnectOptions connectOptions, PoolOptions poolOptions) {
		super(vertx, connectOptions, poolOptions);
	}

	/**
	 * 插入数据
	 *
	 * @param t 插入对象
	 * @return 返回对象, 如果有主键, 填充主键
	 */
	public Future<T> insert(T t) {
		Promise<T> promise = Promise.promise();
		Class<?> aClass = t.getClass();
		MySQLTableName mySqlTableName = aClass.getAnnotation(MySQLTableName.class);
		if (mySqlTableName == null || StringUtils.isEmpty(mySqlTableName.value())) {
			return Future.failedFuture("Unknown table name , use MySQLTableName annotation");
		}
		StringBuilder sqlBuilder = new StringBuilder();
		StringBuilder columnListBuilder = new StringBuilder();
		sqlBuilder.append("insert into ").append(mySqlTableName.value());
		int columnNum = 0;
		ArrayList<Object> params = new ArrayList<>();
		Field mysqlIdField = null;
		for (Field field : aClass.getDeclaredFields()) {
			if (field.getAnnotation(MySQLId.class) != null) {
				mysqlIdField = field;
			}
			if (field.getAnnotation(MySQLExclude.class) != null) {
				continue;
			}
			Object o = null;
			field.setAccessible(true);
			try {
				o = field.get(t);
				if (o == null || "".equals(o)) {
					continue;
				}
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			String columnName = "";
			MySQLColumnName mySqlColumnName = field.getAnnotation(MySQLColumnName.class);
			if (mySqlColumnName != null) {
				columnName = mySqlColumnName.value();
			}
			if (StringUtils.isEmpty(columnName)) {
				//驼峰转_
				columnName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, field.getName());
			}
			columnListBuilder.append(columnName).append(",");
			columnNum++;
			if (o instanceof LocalDate || o instanceof LocalTime || o instanceof LocalDateTime) {
				params.add(o.toString());
			} else {
				params.add(o);
			}
		}
		if (columnNum == 0) {
			//没有字段捣啥乱
			return Future.succeededFuture();
		}
		sqlBuilder.append("(").append(columnListBuilder).deleteCharAt(sqlBuilder.length() - 1)
			.append(") values(").append(StringUtils.repeat("?,", columnNum)).deleteCharAt(sqlBuilder.length() - 1)
			.append(")");
		Field finalMysqlIdField = mysqlIdField;
		super.insertLastId(params, sqlBuilder.toString()).onFailure(promise::fail)
			.onSuccess(s -> {
				if (null != finalMysqlIdField) {
					finalMysqlIdField.setAccessible(true);
					try {
						finalMysqlIdField.set(t, s);
					} catch (IllegalAccessException e) {
						e.printStackTrace();
						promise.fail(e);
					}
				}
				promise.complete(t);
			});
		return promise.future();
	}

	/**
	 * 批量 插入
	 *
	 * @param tList 批量插入对象
	 * @return 返回执行结果,
	 */
	public Future<BatchExecutionResults<T>> insert(List<T> tList) {
		Promise<BatchExecutionResults<T>> promise = Promise.promise();
		BatchExecutionResults<T> tBatchExecutionResults = new BatchExecutionResults<>();
		ArrayList<Future<T>> futures = new ArrayList<>();
		tList.forEach(t -> futures.add(this.insert(t).onFailure(f -> {
			tBatchExecutionResults.getFailedList().add(t);
			tBatchExecutionResults.getException().add(f);
		}).onSuccess(s -> tBatchExecutionResults.getSuccessList().add(s))));
		MyCompositeFuture.join(futures).onComplete(s -> promise.complete(tBatchExecutionResults));
		return promise.future();
	}

	/**
	 * 根据主键删除
	 *
	 * @param t 删除的实体对象
	 * @return 返回处理行数
	 */
	public Future<Integer> delete(T t) {
		Class<?> aClass = t.getClass();
		Future<Integer> future = null;
		for (Field declaredField : aClass.getDeclaredFields()) {
			MySQLId mysqlid = declaredField.getAnnotation(MySQLId.class);
			if (mysqlid != null) {
				try {
					future = deleteById(declaredField.get(t));
				} catch (IllegalAccessException e) {
					e.printStackTrace();
					future = Future.failedFuture(e);
				}
			}
		}
		if (future == null) {
			return Future.failedFuture("主键不存在");
		} else {
			return future;
		}
	}

	/**
	 * 删除函数
	 *
	 * @param object 主键
	 * @return 返回处理结果
	 */
	public Future<Integer> deleteById(Object object) {
		Type genericSuperclass = this.getClass().getGenericSuperclass();
		ParameterizedType pt = (ParameterizedType) genericSuperclass;
		Type actualType = pt.getActualTypeArguments()[0];
		Class<?> clazz = TypeToken.of(actualType).getRawType();
		MySQLTableName mySqlTableName = clazz.getAnnotation(MySQLTableName.class);
		if (mySqlTableName == null || StringUtils.isEmpty(mySqlTableName.value())) {
			return Future.failedFuture("Unknown table name , use MySQLTableName annotation");
		}
		String primary = "";
		for (Field field : clazz.getDeclaredFields()) {
			MySQLId mySQLID = field.getAnnotation(MySQLId.class);
			if (mySQLID != null) {
				MySQLColumnName mySQLColumnName = field.getAnnotation(MySQLColumnName.class);
				if (mySQLColumnName != null && !StringUtils.isEmpty(mySQLColumnName.value())) {
					primary = mySQLColumnName.value();
				} else {
					primary = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, field.getName());
				}
			}
		}
		if (StringUtils.isEmpty(primary)) {
			return Future.failedFuture("PRIMARY Undefined , Use MySQLId annotation ");
		}
		String stringBuilder = "delete from " + mySqlTableName.value() +
			" where " + primary + " = ?";
		return super.executeNoResult(object, stringBuilder);
	}

	/**
	 * 根据id更新对象,setNull is false
	 *
	 * @param t 实体对象
	 * @return 返回影响的行数
	 */
	public Future<Integer> update(T t) {
		return update(t, false);
	}

	/**
	 * 根据id更新对象
	 *
	 * @param t       实体对象
	 * @param setNull true 如果更新参数属性为null,更新为null
	 *                false  如果更新参数属性为null,不更新此字段
	 * @return 返回影响的行数
	 */
	public Future<Integer> update(T t, boolean setNull) {
		Class<?> aClass = t.getClass();
		MySQLTableName mySQLTableName = aClass.getAnnotation(MySQLTableName.class);
		if (mySQLTableName == null || StringUtils.isEmpty(mySQLTableName.value())) {
			return Future.failedFuture("Unknown table name , use MySQLTableName annotation");
		}
		Optional<Field> first = Arrays.stream(aClass.getDeclaredFields()).filter(f -> f.getAnnotation(MySQLId.class) != null).findFirst();
		if (!first.isPresent()) {
			return Future.failedFuture("Unknown primary key , use MySQLId annotation");
		}
		Field primaryField = first.get();
		String primaryName;
		Object primaryValue;
		primaryField.setAccessible(true);
		try {
			primaryName = primaryField.getName();
			primaryValue = primaryField.get(t);
		} catch (IllegalAccessException e) {
			e.printStackTrace();
			return Future.failedFuture(e);
		}
		T t2;
		if (StringUtils.isEmpty(primaryName) || primaryValue == null) {
			return Future.failedFuture("Primary key does not exist or is empty！");
		}
		try {
			t2 = (T) aClass.getDeclaredConstructor().newInstance();
			Field declaredField = aClass.getDeclaredField(primaryName);
			declaredField.setAccessible(true);
			declaredField.set(t2, primaryValue);
		} catch (InstantiationException | IllegalAccessException | NoSuchFieldException | NoSuchMethodException | InvocationTargetException e) {
			e.printStackTrace();
			return Future.failedFuture(e);
		}
		return update(t, t2, setNull);
	}

	/**
	 * 更新数据  setNull is false
	 *
	 * @param t  更新参数
	 * @param t2 条件参数
	 * @return * @return 返回受影响的行数
	 */
	public Future<Integer> update(T t, T t2) {
		return update(t, t2, false);
	}

	/**
	 * 更新数据
	 *
	 * @param t       更新参数
	 * @param t2      条件参数
	 * @param setNull true 如果更新参数属性为null,更新为null
	 *                false  如果更新参数属性为null,不更新此字段
	 * @return 返回受影响的行数
	 */
	public Future<Integer> update(T t, T t2, boolean setNull) {
		Class<?> aClass = t.getClass();
		MySQLTableName mySQLTableName = aClass.getAnnotation(MySQLTableName.class);
		if (mySQLTableName == null || StringUtils.isEmpty(mySQLTableName.value())) {
			return Future.failedFuture("Unknown table name , use MySQLTableName annotation");
		}
		//拼接 sql
		StringBuilder sqlBuilder = new StringBuilder();
		StringBuilder whereBuilder = new StringBuilder();
		ArrayList<Object> params = new ArrayList<>();
		ArrayList<Object> whereParams = new ArrayList<>();
		sqlBuilder.append("update ").append(mySQLTableName.value()).append(" set ");
		Field[] fields = aClass.getDeclaredFields();
		for (Field field : fields) {
			if (field.getAnnotation(MySQLExclude.class) != null) {
				continue;
			}
			try {
				if (field.getAnnotation(MySQLId.class) == null) {
					field.setAccessible(true);
					Object o = field.get(t);
					if (o == null) {
						if (setNull) {
							sqlBuilder.append(field.getName()).append(" = null ,");
						}
					} else {
						sqlBuilder.append(field.getName()).append(" = ? ,");
						if (o instanceof LocalDate || o instanceof LocalTime || o instanceof LocalDateTime) {
							params.add(o.toString());
						} else {
							params.add(o);
						}
					}
				}
				if (t2 != null) {
					field.setAccessible(true);
					Object o2 = field.get(t2);
					if (o2 == null) {
						continue;
					}
					if (whereBuilder.length() > 0) {
						whereBuilder.append(" and ");
					}
					whereBuilder.append(field.getName()).append(" = ?");
					if (o2 instanceof LocalDate || o2 instanceof LocalTime || o2 instanceof LocalDateTime) {
						whereParams.add(o2.toString());
					} else {
						whereParams.add(o2);
					}
				}
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
		}

		sqlBuilder.deleteCharAt(sqlBuilder.length() - 1).append(" where ").append(whereBuilder);
		if (params.size() == 0) {
			return Future.succeededFuture();
		}
		params.addAll(whereParams);
		return super.executeNoResult(params, sqlBuilder.toString());
	}

	public Future<T> getById(Object o) {
		Promise<T> promise = Promise.promise();
		Type genericSuperclass = this.getClass().getGenericSuperclass();
		ParameterizedType pt = (ParameterizedType) genericSuperclass;
		Type actualType = pt.getActualTypeArguments()[0];
		//T class
		Class<?> aClass = TypeToken.of(actualType).getRawType();
		MySQLTableName mySQLTableName = aClass.getAnnotation(MySQLTableName.class);
		if (mySQLTableName == null || StringUtils.isEmpty(mySQLTableName.value())) {
			return Future.failedFuture("Unknown table name , use MySQLTableName annotation");
		}
		String primary = "";
		StringBuilder columnNames = new StringBuilder();
		for (Field field : aClass.getDeclaredFields()) {
			if (StringUtils.isEmpty(primary)) {
				MySQLId mySQLID = field.getAnnotation(MySQLId.class);
				if (mySQLID != null) {
					MySQLColumnName mySQLColumnName = field.getAnnotation(MySQLColumnName.class);
					if (mySQLColumnName != null && !StringUtils.isEmpty(mySQLColumnName.value())) {
						primary = mySQLColumnName.value();
					} else {
						primary = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, field.getName());
					}
				}
			}
			MySQLExclude annotation = field.getAnnotation(MySQLExclude.class);
			if (annotation == null) {
				MySQLColumnName mySQLColumnName = field.getAnnotation(MySQLColumnName.class);
				if (mySQLColumnName != null && !StringUtils.isEmpty(mySQLColumnName.value())) {
					columnNames.append(mySQLColumnName.value()).append(",");
				} else {
					columnNames.append(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, field.getName())).append(",");
				}
			}
		}
		columnNames.deleteCharAt(columnNames.length() - 1);
		if (StringUtils.isEmpty(primary)) {
			return Future.failedFuture("PRIMARY Undefined , Use MySQLID annotation ");
		}
		StringBuilder sqlString = new StringBuilder();
		sqlString.append("select ").append(columnNames).append(" from ").append(mySQLTableName.value())
			.append(" where ").append(primary).append(" = ?");
		super.retrieveOne(o, sqlString.toString())
			.onSuccess(s -> {
				try {
					T t = (T) aClass.getDeclaredConstructor().newInstance();
					for (Field field : aClass.getDeclaredFields()) {
						MySQLColumnName columnNameAnnotation = field.getAnnotation(MySQLColumnName.class);
						String columnName;
						if (columnNameAnnotation == null) {
							columnName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, field.getName());
						} else {
							columnName = columnNameAnnotation.value();
						}
						if (s.containsKey(columnName)) {
							field.setAccessible(true);
							Object columnValue = getColumnValue(s, columnName, field);
							field.set(t, columnValue);
						}
					}
					promise.complete(t);
				} catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
					e.printStackTrace();
					promise.fail(e);
				}
			})
			.onFailure(promise::fail);
		return promise.future();
	}

	private Object getColumnValue(JsonObject jsonObject, String columnName, Field field) {
		if (Integer.class.equals(field.getType())) {
			return jsonObject.getInteger(columnName);
		} else if (String.class.equals(field.getType())) {
			return jsonObject.getString(columnName);
		} else if (Long.class.equals(field.getType())) {
			return jsonObject.getLong(columnName);
		} else if (Boolean.class.equals(field.getType())) {
			return jsonObject.getBoolean(columnName);
		} else if (Double.class.equals(field.getType())) {
			return jsonObject.getDouble(columnName);
		} else if (Float.class.equals(field.getType())) {
			return jsonObject.getFloat(columnName);
		} else if (LocalDateTime.class.equals(field.getType())) {
			return LocalDateTime.parse(jsonObject.getString(columnName));
		} else if (LocalDate.class.equals(field.getType())) {
			return LocalDate.parse(jsonObject.getString(columnName));
		} else if (LocalTime.class.equals(field.getType())) {
			return LocalTime.parse(jsonObject.getString(columnName));
		} else {
			return jsonObject.getValue(columnName);
		}

	}


}
