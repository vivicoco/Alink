package com.alibaba.alink.common.utils;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.ml.api.misc.param.Params;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.apache.flink.util.StringUtils;

import com.alibaba.alink.common.mapper.FlatMapper;
import com.alibaba.alink.operator.common.io.types.FlinkTypeConverter;
import com.alibaba.alink.params.dataproc.JsonValueParams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jayway.jsonpath.JsonPath;

import java.lang.reflect.Type;
import java.util.Arrays;

/**
 * the mapper of json extraction transform.
 */
public class JsonPathMapper extends FlatMapper {

	private static final long serialVersionUID = 2298589947480476734L;
	private static Gson gson = new GsonBuilder().disableHtmlEscaping().create();

	private String[] jsonPaths;
	private boolean skipFailed;
	private String[] outputColNames;
	private Type[] outputColTypes;
	private OutputColsHelper outputColsHelper;
	private int idx;

	public JsonPathMapper(TableSchema dataSchema, Params params) {
		super(dataSchema, params);
		String selectedColName = this.params.get(JsonValueParams.SELECTED_COL);
		this.idx = TableUtil.findColIndexWithAssertAndHint(dataSchema.getFieldNames(), selectedColName);
		outputColNames = params.get(JsonValueParams.OUTPUT_COLS);
		jsonPaths = params.get(JsonValueParams.JSON_PATHS);

		skipFailed = params.get(JsonValueParams.SKIP_FAILED);
		for (int i = 0; i < outputColNames.length; ++i) {
			outputColNames[i] = outputColNames[i].trim();
		}

		if (jsonPaths.length != outputColNames.length) {
			throw new IllegalArgumentException(
				"jsonPath and outputColName mismatch: " + jsonPaths.length + " vs " + outputColNames.length);
		}

		int numField = jsonPaths.length;
		TypeInformation[] types = new TypeInformation[numField];
		outputColTypes = new Type[numField];

		if (params.contains(JsonValueParams.OUTPUT_COL_TYPES)) {
			String[] outputColTypeStrs = params.get(JsonValueParams.OUTPUT_COL_TYPES);

			for (int i = 0; i < numField; i++) {
				types[i] = FlinkTypeConverter.getFlinkType(
					outputColTypeStrs[i].trim().toUpperCase()
				);

				outputColTypes[i] = types[i].getTypeClass();
			}
		} else {
			Arrays.fill(types, Types.STRING);
			Arrays.fill(outputColTypes, Types.STRING.getTypeClass());
		}
		this.outputColsHelper = new OutputColsHelper(dataSchema, outputColNames, types,
			this.params.get(JsonValueParams.RESERVED_COLS));
	}

	@Override
	public void flatMap(Row row, Collector <Row> output) throws Exception {
		Row res = new Row(jsonPaths.length);

		String json = (String) row.getField(idx);
		if (StringUtils.isNullOrWhitespaceOnly(json)) {
			if (skipFailed) {
				output.collect(outputColsHelper.getResultRow(row, res));
			} else {
				throw new RuntimeException("empty json string");
			}
		} else {
			for (int i = 0; i < jsonPaths.length; i++) {
				if (outputColTypes[i].equals(Types.STRING.getTypeClass())) {
					try {
						Object obj = JsonPath.read(json, jsonPaths[i]);
						if (!(obj instanceof String)) {
							obj = JsonConverter.toJson(obj);
						}
						res.setField(i, obj);
					} catch (Exception ex) {
						if (skipFailed) {
							res.setField(i, null);
						} else {
							throw new IllegalStateException("Fail to get json path: " + ex);
						}
					}
				} else {
					try {
						res.setField(
							i,
							JsonConverter.fromJson(
								JsonConverter.toJson(JsonPath.read(json, jsonPaths[i])),
								outputColTypes[i]
							)
						);
					} catch (Exception ex) {
						if (skipFailed) {
							res.setField(i, null);
						} else {
							throw new IllegalStateException("Fail to get json path: " + ex);
						}
					}
				}
			}
			output.collect(outputColsHelper.getResultRow(row, res));
		}
	}

	@Override
	public TableSchema getOutputSchema() {
		return outputColsHelper.getResultSchema();
	}
}
