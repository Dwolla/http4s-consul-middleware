object SuppressKindPolymorphicInferAnyOn213 {
  def apply(s: String): Seq[String] = s match {
    case V(V(2, 13, Some(patch), _)) if patch >= 17 =>
      // https://github.com/scala/bug/issues/13128#issuecomment-3375870295
      Seq("-Wconf:cat=lint-infer-any&msg=kind-polymorphic:s")
    case _ =>
      Seq.empty
  }
}
