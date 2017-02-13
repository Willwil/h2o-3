package hex.glm;

import hex.DataInfo;
import hex.ModelMetrics;
import water.Job;
import water.MRTask;
import water.fvec.ChunkAry;
import water.fvec.NewChunkAry;
import water.util.FrameUtils;

import java.util.Arrays;

/**
 * Created by tomas on 3/15/16.
 */
public class GLMScore extends MRTask<GLMScore> {
  final GLMModel _m;
  final Job _j;
  ModelMetrics.MetricBuilder _mb;
  final DataInfo _dinfo;
  final boolean _sparse;
  final String[] _domain;
  final boolean _computeMetrics;
  final boolean _generatePredictions;

  public GLMScore(Job j, GLMModel m, DataInfo dinfo, String[] domain, boolean computeMetrics, boolean generatePredictions) {
    _j = j;
    _m = m;
    _dinfo = dinfo;
    _computeMetrics = computeMetrics;
    _sparse = FrameUtils.sparseRatio(dinfo._adaptedFrame) < .5;
    _domain = domain;
    _generatePredictions = generatePredictions;
  }

  private void processRow(DataInfo.Row r, float [] res, double [] ps) {
    if(_dinfo._responses != 0)res[0] = (float) r.response[0];
    if (r.predictors_bad) {
      Arrays.fill(ps,Double.NaN);
    } else if(r.weight == 0) {
      Arrays.fill(ps,0);
    } else {
      _m.scoreRow(r, r.offset, ps);
      if (_computeMetrics && !r.response_bad)
        _mb.perRow(ps, res, r.weight, r.offset, _m);
    }
  }

  private void map2(ChunkAry chks, NewChunkAry preds){
    if (isCancelled() || _j != null && _j.stop_requested()) return;
    double[] ps;
    if (_computeMetrics) {
      _mb = _m.makeMetricBuilder(_domain);
      ps = _mb._work;  // Sized for the union of test and train classes
    } else
      ps = new double[_m._output._nclasses+1];
    float[] res = new float[1];
    final int nc = _m._output.nclasses();
    final int ncols = nc == 1 ? 1 : nc + 1; // Regression has 1 predict col; classification also has class distribution
    DataInfo.Rows rows = _dinfo.rows(chks,_sparse);
    for(int i = 0; i <chks._len; ++i){
      processRow(rows.row(i), res, ps);
      if(_generatePredictions){
        for(int j = 0; j < ncols; j++)
          preds.addNum(j,ps[j]);
      }
    }
    if (_j != null) _j.update(1);
  }
  public void map(ChunkAry chks){
    assert !_generatePredictions;
    assert _computeMetrics;
    map2(chks,null);
  }
  public void map(ChunkAry chks, NewChunkAry preds) {
    assert _generatePredictions;
    map2(chks,preds);
  }

  @Override
  public void reduce(GLMScore bs) {
    if (_computeMetrics) _mb.reduce(bs._mb);
  }

  @Override
  protected void postGlobal() {
    if (_computeMetrics)_mb.postGlobal();
  }
}


