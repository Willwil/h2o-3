package hex;

import hex.genmodel.utils.DistributionFamily;
import water.IcedUtils;
import water.MRTask;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.*;
import water.util.ArrayUtils;
import water.util.MathUtils;

public class ModelMetricsRegression extends ModelMetricsSupervised {
  public double residual_deviance() { return _mean_residual_deviance; }
  public final double _mean_residual_deviance;
  public final double _mean_absolute_error;
  public double mae() { return _mean_absolute_error; }
  public final double _root_mean_squared_log_error;
  public double rmsle() { return _root_mean_squared_log_error; }
  public ModelMetricsRegression(Model model, Frame frame, long nobs, double mse, double sigma, double mae,double rmsle, double meanResidualDeviance) {
    super(model, frame, nobs, mse, null, sigma);
    _mean_residual_deviance = meanResidualDeviance;
    _mean_absolute_error = mae;
    _root_mean_squared_log_error = rmsle;
  }

  public static ModelMetricsRegression getFromDKV(Model model, Frame frame) {
    ModelMetrics mm = ModelMetrics.getFromDKV(model, frame);

    if (! (mm instanceof ModelMetricsRegression))
      throw new H2OIllegalArgumentException("Expected to find a Regression ModelMetrics for model: " + model._key.toString() + " and frame: " + frame._key.toString(),
              "Expected to find a ModelMetricsRegression for model: " + model._key.toString() + " and frame: " + frame._key.toString() + " but found a: " + mm.getClass());

    return (ModelMetricsRegression) mm;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(super.toString());
    sb.append(" mean residual deviance: " + (float)_mean_residual_deviance + "\n");
    sb.append(" mean absolute error: " + (float)_mean_absolute_error + "\n");
    sb.append(" root mean squared log error: " + (float)_root_mean_squared_log_error + "\n");
    return sb.toString();
  }

  /**
   * Build a Regression ModelMetrics object from predicted and actual targets
   * @param predicted A Vec containing predicted values
   * @param actual A Vec containing the actual target values
   * @return ModelMetrics object
   */
  static public ModelMetricsRegression make(VecAry predicted, VecAry actual, DistributionFamily family) {
    if (predicted == null || actual == null)
      throw new IllegalArgumentException("Missing actual or predicted targets for regression metrics!");
    if (!predicted.isNumeric(0))
      throw new IllegalArgumentException("Predicted values must be numeric for regression metrics.");
    if (!actual.isNumeric(0))
      throw new IllegalArgumentException("Actual values must be numeric for regression metrics.");
    if (family == DistributionFamily.quantile || family == DistributionFamily.tweedie || family == DistributionFamily.huber)
      throw new IllegalArgumentException("Unsupported distribution family, requires additional parameters which cannot be specified right now.");
    Frame predsActual = new Frame(predicted);
    predsActual.add("actual", actual);
    MetricBuilderRegression mb = new RegressionMetrics(family).doAll(predsActual)._mb;
    ModelMetricsRegression mm = (ModelMetricsRegression)mb.makeModelMetrics(null, predsActual, null, null);
    mm._description = "Computed on user-given predictions and targets, distribution: " + (family ==null? DistributionFamily.gaussian.toString(): family.toString()) + ".";
    return mm;
  }



  // helper to build a ModelMetricsRegression for a N-class problem from a Frame that contains N per-class probability columns, and the actual label as the (N+1)-th column
  private static class RegressionMetrics extends MRTask<RegressionMetrics> {
    public MetricBuilderRegression _mb;
    final Distribution _distribution;
    RegressionMetrics(DistributionFamily family) {
      _distribution = family ==null ? new Distribution(DistributionFamily.gaussian) : new Distribution(family);
    }
    @Override public void map(ChunkAry cs) {
      Chunk [] chks = cs.getChunks();
      _mb = new MetricBuilderRegression(_distribution);
      Chunk preds = chks[0];
      Chunk actuals = chks[1];
      double [] ds = new double[1];
      for (int i=0;i<cs._len;++i) {
        ds[0] = preds.atd(i);
        _mb.perRow(ds, new float[]{(float)actuals.atd(i)}, null);
      }
    }
    @Override public void reduce(RegressionMetrics mrt) { _mb.reduce(mrt._mb); }
  }

  public static class MetricBuilderRegression<T extends MetricBuilderRegression<T>> extends MetricBuilderSupervised<T> {
    double _sumdeviance;
    Distribution _dist;
    double _abserror;
    double _rmslerror;
    public MetricBuilderRegression() {
      super(1,null); //this will make _work = new float[2];
    }
    public MetricBuilderRegression(Distribution dist) {
      super(1,null); //this will make _work = new float[2];
      _dist=dist;
    }

    // ds[0] has the prediction and ds[1] is ignored
    @Override public double[] perRow(double ds[], float[] yact, Model m) {return perRow(ds, yact, 1, 0, m);}
    @Override public double[] perRow(double ds[], float[] yact, double w, double o,  Model m) {
      if( Float.isNaN(yact[0]) ) return ds; // No errors if   actual   is missing
      if(ArrayUtils.hasNaNs(ds)) return ds;  // No errors if prediction has missing values (can happen for GLM)
      if(w == 0 || Double.isNaN(w)) return ds;
      // Compute error
      double err = yact[0] - ds[0]; // Error: distance from the actual
      double err_msle = Math.pow(Math.log1p(ds[0]) - Math.log1p(yact[0]),2); //Squared log error
      _sumsqe += w*err*err;       // Squared error
      _abserror += w*Math.abs(err);
      _rmslerror += w*err_msle;
      assert !Double.isNaN(_sumsqe);
      if (m != null && m._parms._distribution != DistributionFamily.huber)
        _sumdeviance += m.deviance(w, yact[0], ds[0]);
      else if (_dist!=null)
        _sumdeviance += _dist.deviance(w, yact[0], ds[0]);
      _count++;
      _wcount += w;
      _wY += w*yact[0];
      _wYY += w*yact[0]*yact[0];
      return ds;                // Flow coding
    }

    @Override public void reduce( T mb ) {
      super.reduce(mb);
      _sumdeviance += mb._sumdeviance;
      _abserror += mb._abserror;
      _rmslerror += mb._rmslerror;
    }

    // Having computed a MetricBuilder, this method fills in a ModelMetrics
    public ModelMetrics makeModelMetrics(Model m, Frame f, Frame adaptedFrame, Frame preds) {
      double mse = _sumsqe / _wcount;
      double mae = _abserror/_wcount; //Mean Absolute Error
      double rmsle = Math.sqrt(_rmslerror/_wcount); //Root Mean Squared Log Error
      if (adaptedFrame ==null) adaptedFrame = f;
      double meanResDeviance = 0;

      if (m != null && m._parms._distribution == DistributionFamily.huber) {
        assert(_sumdeviance==0); // should not yet be computed
        VecAry actual_and_weight = new VecAry();
        if (preds != null) {
          VecAry actual = adaptedFrame.vec(m._parms._response_column);
          if(actual != null) actual_and_weight.append(actual);
          VecAry weight = adaptedFrame.vec(m._parms._weights_column);
          if(weight != null)actual_and_weight.append(weight);
          //compute huber delta based on huber alpha quantile on absolute prediction error
          double huberDelta = computeHuberDelta(actual, preds.vecs(), weight, m._parms._huber_alpha);
          // make a deep copy of the model's current distribution state (huber delta)
          _dist = IcedUtils.deepCopy(m._dist);
          _dist.setHuberDelta(huberDelta);
          meanResDeviance = new MeanResidualDeviance(_dist, new VecAry(preds.vecs()).append(actual_and_weight)).exec().meanResidualDeviance;
        }
      } else {
        meanResDeviance = _sumdeviance / _wcount; //mean residual deviance
      }
      ModelMetricsRegression mm = new ModelMetricsRegression(m, f, _count, mse, weightedSigma(), mae, rmsle, meanResDeviance);
      if (m!=null) m.addModelMetrics(mm);
      return mm;
    }
  }

  public static double computeHuberDelta(VecAry actual, VecAry preds, VecAry weight, double huberAlpha) {
    VecAry absdiff = new MRTask() {
      @Override
      public void map(ChunkAry cs, NewChunkAry nc) {
        for (int i = 0; i < cs._len; ++i)
          nc.addNum(0,Math.abs(cs.atd(i,0) - cs.atd(i,1)));
      }
    }.doAll(1, (byte) 3, new Frame(new String[]{"preds", "actual"},new VecAry(preds).append(actual))).outputFrame().vecs();
    // make a deep copy of the model's current distribution state (huber delta)
    //compute huber delta based on huber alpha quantile on absolute prediction error
    double hd = MathUtils.computeWeightedQuantile(weight, absdiff, huberAlpha);
    absdiff.remove();
    return hd;
  }
}
